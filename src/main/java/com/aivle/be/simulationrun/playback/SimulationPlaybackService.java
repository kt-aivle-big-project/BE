package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SimulationPlaybackService {

    private static final Logger log =
            LoggerFactory.getLogger(SimulationPlaybackService.class);
    private static final String RUN_TOPIC = "/topic/simulation-runs";
    private static final Set<String> SUPPORTED_STEP_TYPES = Set.of(
            "MOVE", "WAIT", "SERVICE"
    );
    private static final Set<String> SUPPORTED_SERVICE_KINDS = Set.of(
            "PICKUP", "DROP", "STATION", "RETURN",
            "EMPTY_TOTE_BUFFER", "PARK", "CHARGE"
    );

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final RobotRepository robotRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final PlanTaskLifecycleService planTaskLifecycleService;

    private final Map<Long, LaroPlaybackContext> contexts =
            new ConcurrentHashMap<>();
    private final Map<Long, String> nodeCodeCache =
            new ConcurrentHashMap<>();
    private final Map<Long, Long> completedClockMillis =
            new ConcurrentHashMap<>();

    @Transactional
    public void installLaroPlan(
            Long simulationRunId,
            LaroPlanResponse response
    ) {
        SimulationRun run = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Simulation run not found: " + simulationRunId
                ));
        if (run.getStatus() != SimulationRunStatus.RUNNING
                && run.getStatus() != SimulationRunStatus.PAUSED) {
            throw new IllegalStateException(
                    "LARO plan can only be installed on a running or paused simulation"
            );
        }
        if (response == null || !response.isValidated()
                || response.plan().robots() == null
                || response.plan().robots().isEmpty()) {
            throw new IllegalArgumentException("Validated LARO plan is required");
        }

        Long warehouseId = run.getWarehouse().getId();
        Map<Long, Robot> robotsById = robotRepository
                .findAllByWarehouse_Id(warehouseId)
                .stream()
                .collect(Collectors.toMap(Robot::getId, robot -> robot));
        Map<String, Long> robotIds = new HashMap<>();
        Map<Long, RobotRuntime> robotRuntimes = new HashMap<>();

        for (LaroPlanResponse.RobotPlan robotPlan : response.plan().robots()) {
            Long robotId = parseBeRobotId(robotPlan.robotId());
            Robot robot = robotsById.get(robotId);
            if (robot == null) {
                throw new IllegalArgumentException(
                        "LARO robot does not exist in BE warehouse: "
                                + robotPlan.robotId()
                );
            }
            robotIds.put(robotPlan.robotId(), robotId);
            int battery = simulationRunStateStore
                    .findByRobotId(simulationRunId, robotId)
                    .map(RobotState::batteryLevel)
                    .orElse(robot.getBattery());
            robotRuntimes.put(
                    robotId,
                    new RobotRuntime(
                            robotId,
                            robot.getNodeId(),
                            battery,
                            robot.getRobotSpec().getBaseBatteryRate(),
                            robot.getRobotSpec().getWorkBatteryRate()
                    )
            );
        }

        Map<String, Long> nodeIds = warehouseNodeRepository
                .findAllByWarehouse_Id(warehouseId)
                .stream()
                .filter(node -> node.getNodeCode() != null)
                .collect(Collectors.toMap(
                        WarehouseNode::getNodeCode,
                        WarehouseNode::getId
                ));
        addLaroNodeAliases(response.plan().robots(), nodeIds);
        validateLaroNodes(response.plan().robots(), nodeIds);
        cacheNodeCodes(warehouseId);
        planTaskLifecycleService.installAssignments(
                simulationRunId,
                response.plan().logicalOperations()
        );

        List<LaroPlaybackContext.ScheduledStep> steps =
                LaroPlaybackContext.flatten(response.plan().robots());
        double speed = run.getSimulationSpeed() == null
                ? 1.0
                : run.getSimulationSpeed();
        contexts.put(
                simulationRunId,
                new LaroPlaybackContext(
                        simulationRunId,
                        warehouseId,
                        response.plan().planId(),
                        steps,
                        robotIds,
                        nodeIds,
                        robotRuntimes,
                        speed
                )
        );
        completedClockMillis.remove(simulationRunId);

        log.info(
                "[LARO playback] runId={} planId={} robots={} steps={}",
                simulationRunId,
                response.plan().planId(),
                response.plan().robots().size(),
                steps.size()
        );
    }

    @Transactional
    public void tick(long tickMillis) {
        for (Long runId : List.copyOf(contexts.keySet())) {
            LaroPlaybackContext context = contexts.get(runId);
            if (context == null) {
                continue;
            }

            SimulationRun run = simulationRunRepository.findById(runId).orElse(null);
            if (run == null || isTerminated(run.getStatus())) {
                contexts.remove(runId);
                continue;
            }
            if (run.getStatus() != SimulationRunStatus.RUNNING) {
                continue;
            }

            context.advanceClock(tickMillis);
            for (LaroPlaybackContext.StepEvent event : context.pollDueEvents()) {
                publishEvent(context, event);
            }

            if (context.isFinished()) {
                completedClockMillis.put(runId, context.getClockMillis());
                planTaskLifecycleService.completeAssignedTasks(runId);
                contexts.remove(runId);
                log.info(
                        "[LARO playback] runId={} planId={} completed at {}ms",
                        runId,
                        context.getPlanId(),
                        context.getClockMillis()
                );
            }
        }
    }

    private void publishEvent(
            LaroPlaybackContext context,
            LaroPlaybackContext.StepEvent event
    ) {
        LaroPlaybackContext.ScheduledStep scheduled = event.scheduledStep();
        LaroPlanResponse.PlanStep step = scheduled.step();
        Long robotId = context.getRobotIds().get(scheduled.robotExternalId());
        if (robotId == null) {
            return;
        }

        RobotState state = switch (step.stepType()) {
            case "MOVE" -> moveState(context, robotId, step, event.start());
            case "WAIT" -> waitState(context, robotId, step);
            case "SERVICE" -> serviceState(
                    context,
                    robotId,
                    step,
                    event.start()
            );
            default -> throw new IllegalArgumentException(
                    "Unsupported LARO step type: " + step.stepType()
            );
        };
        publish(context.getSimulationRunId(), state);
    }

    private RobotState waitState(
            LaroPlaybackContext context,
            Long robotId,
            LaroPlanResponse.PlanStep step
    ) {
        Long nodeId = context.getNodeIds().get(step.nodeId());
        return RobotState.stationary(
                robotId,
                context.getWarehouseId(),
                nodeId,
                nodeCodeCache.get(nodeId),
                context.getRobotRuntimes().get(robotId).batteryPercent(),
                RobotStatus.IDLE,
                null,
                LocalDateTime.now()
        );
    }

    private RobotState moveState(
            LaroPlaybackContext context,
            Long robotId,
            LaroPlanResponse.PlanStep step,
            boolean start
    ) {
        String externalCurrentCode = start
                ? step.fromNode()
                : step.toNode();
        Long currentNodeId = context.getNodeIds().get(externalCurrentCode);
        String currentNodeCode = nodeCodeCache.get(currentNodeId);
        Long nextNodeId = start
                ? context.getNodeIds().get(step.toNode())
                : null;
        String nextNodeCode = start
                ? nodeCodeCache.get(nextNodeId)
                : null;
        double durationSeconds = Math.max(
                0,
                step.endAtMs() - step.startAtMs()
        ) / 1000.0 / context.getSpeed();
        RobotRuntime runtime = context.getRobotRuntimes().get(robotId);
        if (start) {
            if (!runtime.canMove()) {
                return stationaryError(
                        context,
                        robotId,
                        currentNodeId,
                        currentNodeCode
                );
            }
            runtime.consumeMoveBattery();
        }

        return new RobotState(
                robotId,
                context.getWarehouseId(),
                currentNodeId,
                currentNodeCode,
                nextNodeId,
                nextNodeCode,
                start ? durationSeconds : null,
                runtime.batteryPercent(),
                start ? RobotStatus.MOVING : RobotStatus.IDLE,
                null,
                LocalDateTime.now()
        );
    }

    private RobotState serviceState(
            LaroPlaybackContext context,
            Long robotId,
            LaroPlanResponse.PlanStep step,
            boolean start
    ) {
        Long nodeId = context.getNodeIds().get(step.nodeId());
        String nodeCode = nodeCodeCache.get(nodeId);
        RobotRuntime runtime = context.getRobotRuntimes().get(robotId);
        if (start) {
            runtime.consumeWorkBattery();
        }

        return RobotState.stationary(
                robotId,
                context.getWarehouseId(),
                nodeId,
                nodeCode,
                runtime.batteryPercent(),
                start
                        ? serviceStatus(step.serviceKind())
                        : RobotStatus.IDLE,
                null,
                LocalDateTime.now()
        );
    }

    private RobotState stationaryError(
            LaroPlaybackContext context,
            Long robotId,
            Long nodeId,
            String nodeCode
    ) {
        return RobotState.stationary(
                robotId,
                context.getWarehouseId(),
                nodeId,
                nodeCode,
                0,
                RobotStatus.ERROR,
                null,
                LocalDateTime.now()
        );
    }

    private RobotStatus serviceStatus(String serviceKind) {
        return switch (serviceKind) {
            case "PICKUP" -> RobotStatus.PICKING;
            case "DROP" -> RobotStatus.PUTAWAY;
            case "RETURN", "EMPTY_TOTE_BUFFER" -> RobotStatus.RELOCATION;
            case "CHARGE" -> RobotStatus.CHARGING;
            case "PARK" -> RobotStatus.IDLE;
            case "STATION" -> RobotStatus.WORKING;
            default -> throw new IllegalArgumentException(
                    "Unsupported LARO service kind: " + serviceKind
            );
        };
    }

    public void clear(Long simulationRunId) {
        contexts.remove(simulationRunId);
    }

    public boolean markRobotError(Long simulationRunId, Long robotId) {
        LaroPlaybackContext context = contexts.get(simulationRunId);
        if (context == null || robotId == null) {
            return false;
        }

        RobotState current = simulationRunStateStore
                .findByRobotId(simulationRunId, robotId)
                .orElse(null);
        if (current == null) {
            return false;
        }

        publish(
                simulationRunId,
                RobotState.stationary(
                        robotId,
                        context.getWarehouseId(),
                        current.currentNodeId(),
                        current.currentNodeCode(),
                        current.batteryLevel(),
                        RobotStatus.ERROR,
                        current.currentTaskId(),
                        LocalDateTime.now()
                )
        );
        return true;
    }

    public boolean changeSpeed(Long simulationRunId, double newSpeed) {
        LaroPlaybackContext context = contexts.get(simulationRunId);
        if (context == null) {
            return false;
        }
        context.changeSpeed(newSpeed);
        return true;
    }

    public boolean isPlaying(Long simulationRunId) {
        return contexts.containsKey(simulationRunId);
    }

    public long currentClockMillis(Long simulationRunId) {
        LaroPlaybackContext context = contexts.get(simulationRunId);
        return context == null
                ? completedClockMillis.getOrDefault(simulationRunId, 0L)
                : context.getClockMillis();
    }

    private void publish(Long simulationRunId, RobotState state) {
        simulationRunStateStore.save(simulationRunId, state);
        messagingTemplate.convertAndSend(
                robotTopic(simulationRunId),
                RobotStateResponse.from(state)
        );
    }

    private Long parseBeRobotId(String robotId) {
        try {
            return Long.valueOf(robotId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "LARO plan must return the BE numeric robot ID: " + robotId,
                    exception
            );
        }
    }

    private void validateLaroNodes(
            List<LaroPlanResponse.RobotPlan> robotPlans,
            Map<String, Long> nodeIds
    ) {
        for (LaroPlanResponse.RobotPlan robotPlan : robotPlans) {
            requireNode(robotPlan.initialNode(), nodeIds);
            for (LaroPlanResponse.PlanStep step : robotPlan.steps()) {
                if (step.stepType() == null
                        || !SUPPORTED_STEP_TYPES.contains(step.stepType())) {
                    throw new IllegalArgumentException(
                            "Unsupported LARO step type: " + step.stepType()
                    );
                }
                if ("MOVE".equals(step.stepType())) {
                    requireNode(step.fromNode(), nodeIds);
                    requireNode(step.toNode(), nodeIds);
                } else {
                    requireNode(step.nodeId(), nodeIds);
                }
                if ("SERVICE".equals(step.stepType())
                        && (step.serviceKind() == null
                        || !SUPPORTED_SERVICE_KINDS.contains(
                                step.serviceKind()
                        ))) {
                    throw new IllegalArgumentException(
                            "Unsupported LARO service kind: "
                                    + step.serviceKind()
                    );
                }
            }
        }
    }

    private void requireNode(
            String nodeCode,
            Map<String, Long> nodeIds
    ) {
        if (nodeCode == null || !nodeIds.containsKey(nodeCode)) {
            throw new IllegalArgumentException(
                    "Unknown LARO node: " + nodeCode
            );
        }
    }

    private void addLaroNodeAliases(
            List<LaroPlanResponse.RobotPlan> robotPlans,
            Map<String, Long> nodeIds
    ) {
        List<Map.Entry<String, Long>> outboundAccessNodes = nodeIds.entrySet()
                .stream()
                .filter(entry -> entry.getKey().matches("O_\\d+"))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        List<Map.Entry<String, Long>> logicalOutboundNodes = nodeIds.entrySet()
                .stream()
                .filter(entry -> entry.getKey().matches("O_[A-Z]"))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        Set<String> externalCodes = new HashSet<>();

        for (LaroPlanResponse.RobotPlan robotPlan : robotPlans) {
            externalCodes.add(robotPlan.initialNode());
            for (LaroPlanResponse.PlanStep step : robotPlan.steps()) {
                externalCodes.add(step.fromNode());
                externalCodes.add(step.toNode());
                externalCodes.add(step.nodeId());
            }
        }

        for (String externalCode : externalCodes) {
            if (externalCode == null || nodeIds.containsKey(externalCode)) {
                continue;
            }

            String rackCode = externalCode.replaceFirst(
                    "^(K\\d+_\\d+)_ACCESS_[A-Z]+$",
                    "$1"
            );
            if (!rackCode.equals(externalCode)
                    && nodeIds.containsKey(rackCode)) {
                nodeIds.put(externalCode, nodeIds.get(rackCode));
                continue;
            }

            // Some service-only access nodes are intentionally not persisted
            // by the BE warehouse importer. Bind those AI node codes to the
            // adjacent physical node found in the validated MOVE timeline.
            // This covers empty-tote buffers and future facility access nodes
            // without silently accepting arbitrary unknown route nodes.
            if (isVirtualAccessCode(externalCode)) {
                Long adjacentNodeId = findAdjacentPhysicalNodeId(
                        externalCode,
                        robotPlans,
                        nodeIds
                );
                if (adjacentNodeId != null) {
                    nodeIds.put(externalCode, adjacentNodeId);
                    continue;
                }
            }

            if (externalCode.matches(
                    "OUT_STATION_\\d+_ACCESS_[A-Z]+"
            )) {
                int stationNumber = Integer.parseInt(
                        externalCode.replaceFirst(
                                "^OUT_STATION_(\\d+)_.*$",
                                "$1"
                        )
                );
                List<Map.Entry<String, Long>> stationNodes =
                        outboundAccessNodes.isEmpty()
                                ? logicalOutboundNodes
                                : outboundAccessNodes;
                if (stationNumber > 0
                        && stationNumber <= stationNodes.size()) {
                    nodeIds.put(
                            externalCode,
                            stationNodes.get(stationNumber - 1).getValue()
                    );
                }
            }
        }
    }

    private boolean isVirtualAccessCode(String nodeCode) {
        return !nodeCode.startsWith("OUT_STATION_")
                && (nodeCode.endsWith("_ACCESS")
                || nodeCode.matches(".*_ACCESS_[A-Z]+")
                || nodeCode.matches("ETB_\\d+"));
    }

    private Long findAdjacentPhysicalNodeId(
            String virtualNodeCode,
            List<LaroPlanResponse.RobotPlan> robotPlans,
            Map<String, Long> nodeIds
    ) {
        for (LaroPlanResponse.RobotPlan robotPlan : robotPlans) {
            for (LaroPlanResponse.PlanStep step : robotPlan.steps()) {
                if (!"MOVE".equals(step.stepType())) {
                    continue;
                }
                if (virtualNodeCode.equals(step.toNode())
                        && nodeIds.containsKey(step.fromNode())) {
                    return nodeIds.get(step.fromNode());
                }
                if (virtualNodeCode.equals(step.fromNode())
                        && nodeIds.containsKey(step.toNode())) {
                    return nodeIds.get(step.toNode());
                }
            }
        }
        return null;
    }

    private void cacheNodeCodes(Long warehouseId) {
        for (WarehouseNode node :
                warehouseNodeRepository.findAllByWarehouse_Id(warehouseId)) {
            if (node.getNodeCode() != null) {
                nodeCodeCache.put(node.getId(), node.getNodeCode());
            }
        }
    }

    private boolean isTerminated(SimulationRunStatus status) {
        return status == SimulationRunStatus.COMPLETED
                || status == SimulationRunStatus.FAILED
                || status == SimulationRunStatus.STOPPED
                || status == SimulationRunStatus.CREATED;
    }

    private String robotTopic(Long simulationRunId) {
        return RUN_TOPIC + "/" + simulationRunId + "/robots";
    }
}
