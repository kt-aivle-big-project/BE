package com.aivle.be.simulationrun.playback;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.laro.service.LaroPlanMappingException;
import com.aivle.be.laro.service.LaroTaskId;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SimulationPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(SimulationPlaybackService.class);

    private static final String RUN_TOPIC = "/topic/simulation-runs";
    private static final String TASK_TOPIC = "/topic/tasks";

    private static final int MAX_STEPS_PER_TICK = 50;

    // Battery specs describe the real-device rate. Accelerate consumption in the
    static final double SIMULATION_BATTERY_RATE_MULTIPLIER = 10.0;

    // Redis projection publication and playback ticks can briefly cross at the
    private static final int MAX_PLAN_ACTIVATION_ATTEMPTS = 3;

    // 계획 대상 작업 상태
    private static final List<TaskStatus> PLANNABLE_STATUSES =
            List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED);

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final ChargingStationRepository chargingStationRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskService taskService;
    private final WarehousePathFinder pathFinder;
    private final SimpMessagingTemplate messagingTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final LaroInventoryReservationService inventoryReservationService;

    private final Map<Long, PlaybackContext> contexts = new ConcurrentHashMap<>();

    private final Map<Long, AiPlaybackContext> aiContexts = new ConcurrentHashMap<>();

    // A replan is built and validated first, then activated only after every
    private final Map<Long, PendingAiPlan> pendingAiPlans = new ConcurrentHashMap<>();

    private final Map<Long, LowBatteryReplanRequest> lowBatteryReplanRequests =
            new ConcurrentHashMap<>();

    private final Set<Long> lowBatteryInjectionRunIds = ConcurrentHashMap.newKeySet();

    private final Set<Long> suspendedAiRunIds = ConcurrentHashMap.newKeySet();

    private final Map<Long, String> nodeCodeCache = new ConcurrentHashMap<>();

    /* =========================================================
       계획 수립
    ========================================================= */

    private Integer currentBattery(Long simulationRunId, SimulationRun run, Robot robot) {
        Integer live = simulationRunStateStore
                .findByRobotId(simulationRunId, robot.getId())
                .map(RobotState::batteryLevel)
                .orElse(null);

        if (live != null) {
            return live;
        }
        if (run != null && run.getInitialBattery() != null) {
            return run.getInitialBattery();
        }
        return robot.getBattery();
    }

    private Map<Long, Double> availableChargingPowerByNode(Long warehouseId) {
        Map<Long, Double> chargingPowerByNode = new LinkedHashMap<>();
        chargingStationRepository.findAllByWarehouse_Id(warehouseId).stream()
                .filter(station -> station.getNode().isActive())
                .filter(station ->
                        station.getStatus() == ChargingStation.ChargingStationStatus.AVAILABLE
                                && station.getChargingPower() != null
                                && station.getChargingPower() > 0)
                .forEach(station -> chargingPowerByNode.put(
                        station.getNode().getId(),
                        station.getChargingPower()
                ));
        return chargingPowerByNode;
    }

    @Transactional
    public void buildPlan(Long simulationRunId, List<Robot> robots) {
        if (aiContexts.containsKey(simulationRunId)) {
            return;
        }
        SimulationRun run = simulationRunRepository.findById(simulationRunId).orElse(null);
        if (run == null || robots.isEmpty()) {
            return;
        }

        Long warehouseId = run.getWarehouse().getId();

        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        simulationRunId, PLANNABLE_STATUSES);

        if (tasks.isEmpty()) {
            log.info("[재생] runId={} 실행할 작업이 없습니다.", simulationRunId);
            return;
        }

        cacheNodeCodes(warehouseId);

        // 발생 시각 순으로 예약
        List<PlaybackContext.ScheduledTask> scheduled = tasks.stream()
                .map(task -> new PlaybackContext.ScheduledTask(
                        task.getId(),
                        task.effectiveReleaseAtSeconds() * 1000L))
                .sorted(Comparator.comparingLong(PlaybackContext.ScheduledTask::releaseAtMillis))
                .toList();

        List<RobotRuntime> runtimes = robots.stream()
                .map(robot -> new RobotRuntime(
                        robot.getId(),
                        robot.getNodeId(),
                        currentBattery(simulationRunId, run, robot),
                        simulationBatteryRate(robot.getRobotSpec().getBaseBatteryRate()),
                        simulationBatteryRate(robot.getRobotSpec().getWorkBatteryRate())
                ))
                .toList();

        Scenario scenario = run.getScenario();
        double moveSeconds = scenario == null || scenario.getMoveSecondsPerNode() == null
                ? 2.0 : scenario.getMoveSecondsPerNode();
        double pickingSeconds = scenario == null || scenario.getPickingSeconds() == null
                ? 5.0 : scenario.getPickingSeconds();
        double loadingSeconds = scenario == null || scenario.getLoadingSeconds() == null
                ? 5.0 : scenario.getLoadingSeconds();
        Map<Long, Double> chargingPowerByNode = availableChargingPowerByNode(warehouseId);

        double speed = run.getSimulationSpeed() == null ? 1.0 : run.getSimulationSpeed();

        Map<Long, Set<Long>> adjacency = pathFinder.loadAdjacency(warehouseId);

        PlaybackContext context = new PlaybackContext(
                simulationRunId,
                warehouseId,
                adjacency,
                buildAccessNodes(warehouseId, adjacency),
                new ArrayList<>(runtimes),
                scheduled,
                speed,
                moveSeconds,
                pickingSeconds,
                loadingSeconds,
                chargingPowerByNode
        );

        contexts.put(simulationRunId, context);

        log.info("[재생] runId={} 시작 (로봇 {}대, 작업 {}건, 배속 {}x, 이동 {}초/칸)",
                simulationRunId, runtimes.size(), scheduled.size(), speed, moveSeconds);
    }

    /* =========================================================
       재생 진행
    ========================================================= */

    @Transactional
    public void installAiPlan(
            Long simulationRunId,
            LaroPlanResponse.SimulationPlan plan,
            Map<String, Long> aiTaskToBeTask
    ) {
        PreparedAiPlan prepared = prepareAiPlan(simulationRunId, plan, aiTaskToBeTask);
        assignPlannedTasks(prepared.assignedRobotByTask());
        contexts.remove(simulationRunId);
        pendingAiPlans.remove(simulationRunId);
        lowBatteryInjectionRunIds.remove(simulationRunId);
        suspendedAiRunIds.remove(simulationRunId);
        aiContexts.put(simulationRunId, prepared.context());
        logPreparedPlan("installed", prepared);
    }

    @Transactional
    public void stageAiReplan(
            Long simulationRunId,
            LaroPlanResponse.SimulationPlan plan,
            Map<String, Long> aiTaskToBeTask
    ) {
        AiPlaybackContext active = aiContexts.get(simulationRunId);
        if (active == null || plan.basePlanId() == null
                || !plan.basePlanId().equals(active.getPlanId())) {
            throw mappingFailure(
                    "BASE_PLAN_ID_MISMATCH",
                    "simulationRunId", simulationRunId,
                    "planId", plan.planId(),
                    "basePlanId", plan.basePlanId(),
                    "activePlanId", active == null ? null : active.getPlanId()
            );
        }
        PreparedAiPlan prepared = prepareAiPlan(simulationRunId, plan, aiTaskToBeTask);
        Map<String, Long> robotIds = prepared.robotIdsByAiCode();
        if (plan.handoverPoints() != null) {
            for (LaroPlanResponse.HandoverPoint point : plan.handoverPoints()) {
                Long robotId = robotIds.get(point.robotId());
                if (robotId == null) {
                    Long numericId = canonicalRobotDatabaseId(point.robotId());
                    if (numericId != null && active.getRobots().stream()
                            .anyMatch(robot -> numericId.equals(robot.getRobotId()))) {
                        robotId = numericId;
                    }
                }
                Long nodeId = prepared.nodeIdsByCode().get(point.nodeId());
                if (robotId == null || nodeId == null || point.handoverAtMs() == null) {
                    throw mappingFailure(
                            "HANDOVER_POINT_UNRESOLVED",
                            "simulationRunId", simulationRunId,
                            "planId", plan.planId(),
                            "handoverRobotId", point.robotId(),
                            "handoverNodeId", point.nodeId(),
                            "handoverAtMs", point.handoverAtMs(),
                            "resolvedRobotId", robotId,
                            "resolvedNodeId", nodeId
                    );
                }
                active.applyHandover(robotId, point.handoverAtMs(), nodeId);
            }
        }
        pendingAiPlans.put(simulationRunId, new PendingAiPlan(
                prepared.context(),
                prepared.assignedRobotByTask(),
                prepared.registeredNodeByRobot(),
                0
        ));
        logPreparedPlan("staged", prepared);
    }

    private PreparedAiPlan prepareAiPlan(
            Long simulationRunId,
            LaroPlanResponse.SimulationPlan plan,
            Map<String, Long> aiTaskToBeTask
    ) {
        SimulationRun run = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        if (run.getStatus() != SimulationRunStatus.RUNNING
                && run.getStatus() != SimulationRunStatus.QUIESCING
                && run.getStatus() != SimulationRunStatus.REPLANNING
                && run.getStatus() != SimulationRunStatus.PENDING_ACTIVATION) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }
        Long warehouseId = run.getWarehouse().getId();

        List<Robot> participants = simulationRunRobotRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(simulationRunId)
                .stream()
                .map(value -> value.getRobot())
                .toList();
        if (participants.isEmpty() || plan.robots() == null
                || plan.robots().size() > participants.size()) {
            throw mappingFailure(
                    "PLAN_ROBOT_COUNT_INVALID",
                    "simulationRunId", simulationRunId,
                    "planId", plan.planId(),
                    "participantCount", participants.size(),
                    "planRobotCount", plan.robots() == null ? null : plan.robots().size(),
                    "participantRobotIds", participants.stream().map(Robot::getId).toList()
            );
        }

        Map<String, WarehouseNode> nodesByCode = warehouseNodeRepository
                .findAllByWarehouse_IdAndActiveTrue(warehouseId)
                .stream()
                .filter(node -> node.getNodeCode() != null)
                .collect(Collectors.toMap(
                        WarehouseNode::getNodeCode,
                        node -> node,
                        (left, right) -> left
                ));
        cacheNodeCodes(warehouseId);
        Map<Long, Set<Long>> adjacency = pathFinder.loadAdjacency(warehouseId);

        Set<Long> usedRobotIds = new HashSet<>();
        List<AiPlaybackContext.RobotTimeline> timelines = new ArrayList<>();
        Map<Long, Robot> assignedRobotByTask = new HashMap<>();
        Map<String, Long> robotIdsByAiCode = new HashMap<>();
        Map<Long, Long> registeredNodeByRobot = new HashMap<>();

        for (LaroPlanResponse.RobotPlan robotPlan : plan.robots()) {
            Robot robot = resolvePlanRobot(robotPlan.robotId(), participants, usedRobotIds);
            usedRobotIds.add(robot.getId());
            robotIdsByAiCode.put(robotPlan.robotId(), robot.getId());
            if (robot.getNodeId() != null) {
                registeredNodeByRobot.put(robot.getId(), robot.getNodeId());
            }
            List<AiPlaybackContext.TimedStep> steps = convertSteps(
                    simulationRunId,
                    plan.planId(),
                    robotPlan,
                    nodesByCode,
                    adjacency,
                    aiTaskToBeTask
            );
            if (steps.isEmpty()) {
                continue;
            }
            AiPlaybackContext.RobotTimeline timeline = new AiPlaybackContext.RobotTimeline(
                    robot.getId(),
                    steps,
                    resolveInitialNode(robotPlan, nodesByCode, robot),
                    currentBattery(simulationRunId, run, robot),
                    simulationBatteryRate(robot.getRobotSpec().getBaseBatteryRate()),
                    simulationBatteryRate(robot.getRobotSpec().getWorkBatteryRate())
            );
            timelines.add(timeline);
            for (AiPlaybackContext.TimedStep step : steps) {
                if (step.taskId() != null) {
                    assignedRobotByTask.putIfAbsent(step.taskId(), robot);
                }
            }
        }
        if (timelines.isEmpty()) {
            throw new BusinessException(ErrorCode.LARO_PLAN_NOT_EXECUTABLE);
        }

        long initialClock = firstNonNull(
                plan.effectiveFromSimTimeMs(), plan.planStartSimTimeMs(), 0L);
        long finishClock = plan.absoluteFinishAtMs() != null
                ? plan.absoluteFinishAtMs()
                : initialClock + (plan.makespanMs() == null ? 0L : plan.makespanMs());
        long latestStepEnd = timelines.stream()
                .flatMap(timeline -> timeline.getSteps().stream())
                .mapToLong(AiPlaybackContext.TimedStep::endAtMillis)
                .max()
                .orElse(initialClock);
        finishClock = Math.max(finishClock, latestStepEnd);

        AiPlaybackContext context = new AiPlaybackContext(
                simulationRunId,
                warehouseId,
                plan.warehouseId(),
                plan.planId(),
                plan.planVersion(),
                plan.simulationId(),
                initialClock,
                finishClock,
                timelines,
                new HashSet<>(aiTaskToBeTask.values()),
                run.getSimulationSpeed() == null ? 1.0 : run.getSimulationSpeed(),
                run.getChargingThreshold() == null ? 20 : run.getChargingThreshold(),
                availableChargingPowerByNode(warehouseId)
        );
        Map<String, Long> nodeIdsByCode = nodesByCode.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getId()));
        return new PreparedAiPlan(
                context,
                Map.copyOf(assignedRobotByTask),
                Map.copyOf(robotIdsByAiCode),
                Map.copyOf(nodeIdsByCode),
                Map.copyOf(registeredNodeByRobot)
        );
    }

    private void logPreparedPlan(String action, PreparedAiPlan prepared) {
        AiPlaybackContext context = prepared.context();
        log.info("[AI playback] {} runId={}, planId={}, robots={}, steps={}, tasks={}",
                action,
                context.getSimulationRunId(),
                context.getPlanId(),
                context.getRobots().size(),
                context.getRobots().stream().mapToInt(value -> value.getSteps().size()).sum(),
                context.getTaskIds().size());
    }

    private List<AiPlaybackContext.TimedStep> convertSteps(
            Long simulationRunId,
            String planId,
            LaroPlanResponse.RobotPlan robotPlan,
            Map<String, WarehouseNode> nodesByCode,
            Map<Long, Set<Long>> adjacency,
            Map<String, Long> aiTaskToBeTask
    ) {
        if (robotPlan.steps() == null) {
            return List.of();
        }
        List<LaroPlanResponse.PlanStep> ordered = robotPlan.steps().stream()
                .sorted(Comparator
                        .comparing((LaroPlanResponse.PlanStep step) ->
                                step.sequence() == null ? Integer.MAX_VALUE : step.sequence())
                        .thenComparing(step -> step.startAtMs() == null ? Long.MAX_VALUE : step.startAtMs()))
                .toList();
        List<AiPlaybackContext.TimedStep> converted = new ArrayList<>();
        long previousEnd = -1L;
        int fallbackSequence = 0;
        for (LaroPlanResponse.PlanStep step : ordered) {
            if (step.stepType() == null || step.startAtMs() == null || step.endAtMs() == null
                    || step.endAtMs() < step.startAtMs() || step.startAtMs() < previousEnd) {
                throw mappingFailure(
                        "STEP_TIMELINE_INVALID",
                        "simulationRunId", simulationRunId,
                        "planId", planId,
                        "robotId", robotPlan.robotId(),
                        "stepId", step.stepId(),
                        "stepType", step.stepType(),
                        "startAtMs", step.startAtMs(),
                        "endAtMs", step.endAtMs(),
                        "previousEndAtMs", previousEnd
                );
            }
            AiPlaybackContext.StepType type;
            try {
                type = AiPlaybackContext.StepType.valueOf(step.stepType().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw mappingFailure(
                        "STEP_TYPE_UNSUPPORTED",
                        "simulationRunId", simulationRunId,
                        "planId", planId,
                        "robotId", robotPlan.robotId(),
                        "stepId", step.stepId(),
                        "stepType", step.stepType()
                );
            }

            Long nodeId = stepNodeId(
                    simulationRunId, planId, robotPlan.robotId(), step,
                    nodesByCode, step.nodeId(), "nodeId",
                    type != AiPlaybackContext.StepType.MOVE
            );
            Long fromNodeId = stepNodeId(
                    simulationRunId, planId, robotPlan.robotId(), step,
                    nodesByCode, step.fromNode(), "fromNode",
                    type == AiPlaybackContext.StepType.MOVE
            );
            Long toNodeId = stepNodeId(
                    simulationRunId, planId, robotPlan.robotId(), step,
                    nodesByCode, step.toNode(), "toNode",
                    type == AiPlaybackContext.StepType.MOVE
            );
            if (type == AiPlaybackContext.StepType.MOVE
                    && !adjacency.getOrDefault(fromNodeId, Set.of()).contains(toNodeId)) {
                throw mappingFailure(
                        "MOVE_EDGE_NOT_TRAVERSABLE",
                        "simulationRunId", simulationRunId,
                        "planId", planId,
                        "robotId", robotPlan.robotId(),
                        "stepId", step.stepId(),
                        "fromNode", step.fromNode(),
                        "fromNodeId", fromNodeId,
                        "toNode", step.toNode(),
                        "toNodeId", toNodeId
                );
            }

            Long beTaskId = null;
            if (step.taskId() != null) {
                beTaskId = aiTaskToBeTask.get(step.taskId());
                if (beTaskId == null) {
                    beTaskId = aiTaskToBeTask.get(LaroTaskId.base(step.taskId()));
                }
            }
            converted.add(new AiPlaybackContext.TimedStep(
                    step.stepId(),
                    step.sequence() == null ? fallbackSequence : step.sequence(),
                    type,
                    step.startAtMs(),
                    step.endAtMs(),
                    nodeId,
                    fromNodeId,
                    toNodeId,
                    beTaskId,
                    step.serviceKind(),
                    step.reason()
            ));
            fallbackSequence++;
            previousEnd = step.endAtMs();
        }
        return inferStepTasks(converted);
    }

    private List<AiPlaybackContext.TimedStep> inferStepTasks(
            List<AiPlaybackContext.TimedStep> steps
    ) {
        List<AiPlaybackContext.TimedStep> inferred = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            AiPlaybackContext.TimedStep step = steps.get(index);
            Long taskId = step.taskId() == null ? nearestTaskId(steps, index) : step.taskId();
            inferred.add(new AiPlaybackContext.TimedStep(
                    step.stepId(), step.sequence(), step.type(),
                    step.startAtMillis(), step.endAtMillis(),
                    step.nodeId(), step.fromNodeId(), step.toNodeId(),
                    taskId, step.serviceKind(), step.reason()
            ));
        }
        return inferred;
    }

    private Long nearestTaskId(List<AiPlaybackContext.TimedStep> steps, int index) {
        for (int offset = 1; offset < steps.size(); offset++) {
            int next = index + offset;
            if (next < steps.size() && steps.get(next).taskId() != null) {
                return steps.get(next).taskId();
            }
            int previous = index - offset;
            if (previous >= 0 && steps.get(previous).taskId() != null) {
                return steps.get(previous).taskId();
            }
        }
        return null;
    }

    private Long stepNodeId(
            Long simulationRunId,
            String planId,
            String robotId,
            LaroPlanResponse.PlanStep step,
            Map<String, WarehouseNode> nodesByCode,
            String nodeCode,
            String field,
            boolean required
    ) {
        if (nodeCode == null) {
            if (required) {
                throw mappingFailure(
                        "STEP_NODE_MISSING",
                        "simulationRunId", simulationRunId,
                        "planId", planId,
                        "robotId", robotId,
                        "stepId", step.stepId(),
                        "stepType", step.stepType(),
                        "field", field
                );
            }
            return null;
        }
        WarehouseNode node = nodesByCode.get(nodeCode);
        if (node == null && required) {
            throw mappingFailure(
                    "STEP_NODE_UNKNOWN",
                    "simulationRunId", simulationRunId,
                    "planId", planId,
                    "robotId", robotId,
                    "stepId", step.stepId(),
                    "stepType", step.stepType(),
                    "field", field,
                    "nodeCode", nodeCode
            );
        }
        return node == null ? null : node.getId();
    }

    private Long resolveInitialNode(
            LaroPlanResponse.RobotPlan plan,
            Map<String, WarehouseNode> nodesByCode,
            Robot robot
    ) {
        WarehouseNode initial = plan.initialNode() == null ? null : nodesByCode.get(plan.initialNode());
        return initial == null ? robot.getNodeId() : initial.getId();
    }

    static Robot resolvePlanRobot(
            String planRobotId,
            List<Robot> participants,
            Set<Long> usedRobotIds
    ) {
        Long numeric = canonicalRobotDatabaseId(planRobotId);
        if (numeric == null) {
            throw new LaroPlanMappingException(
                    "ROBOT_ID_INVALID",
                    "planRobotId", planRobotId,
                    "participantRobotIds", participants.stream().map(Robot::getId).toList()
            );
        }
        return participants.stream()
                .filter(robot -> numeric.equals(robot.getId()))
                .filter(robot -> !usedRobotIds.contains(robot.getId()))
                .findFirst()
                .orElseThrow(() -> new LaroPlanMappingException(
                        "ROBOT_NOT_PARTICIPATING_OR_DUPLICATED",
                        "planRobotId", planRobotId,
                        "resolvedRobotId", numeric,
                        "participantRobotIds", participants.stream().map(Robot::getId).toList(),
                        "usedRobotIds", usedRobotIds
                ));
    }

    private LaroPlanMappingException mappingFailure(String reason, Object... context) {
        LaroPlanMappingException exception = new LaroPlanMappingException(reason, context);
        log.warn("[AI playback] {}", exception.getMessage());
        return exception;
    }

    static Long canonicalRobotDatabaseId(String value) {
        if (value == null || !value.matches("R[1-9][0-9]*")) {
            return null;
        }
        try {
            Long numeric = Long.valueOf(value.substring(1));
            return value.equals("R" + numeric) ? numeric : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void assignPlannedTasks(Map<Long, Robot> assignedRobotByTask) {
        for (Map.Entry<Long, Robot> entry : assignedRobotByTask.entrySet()) {
            Task task = taskRepository.findById(entry.getKey()).orElse(null);
            if (task == null) {
                continue;
            }
            if (task.getStatus() == TaskStatus.PENDING) {
                task.assignRobot(entry.getValue());
                broadcastTask(task);
            } else if ((task.getStatus() == TaskStatus.ASSIGNED
                    || task.getStatus() == TaskStatus.IN_PROGRESS)
                    && task.getRobot() != null
                    && !entry.getValue().getId().equals(task.getRobot().getId())) {
                task.reassignRobot(entry.getValue());
                broadcastTask(task);
            }
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Transactional
    public void tick(long tickMillis) {
        if (contexts.isEmpty() && aiContexts.isEmpty()) {
            return;
        }

        advanceAiContexts(tickMillis);

        for (Long runId : List.copyOf(contexts.keySet())) {
            PlaybackContext context = contexts.get(runId);
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

            synchronized (context) {
                advance(context, tickMillis);
            }

            if (context.isFinished()) {
                contexts.remove(runId);
                log.info("[재생] runId={} 모든 작업 수행 완료 (시뮬 시각 {}초)",
                        runId, context.clockSeconds());
            }
        }
    }

    private void advanceAiContexts(long tickMillis) {
        for (Long runId : List.copyOf(aiContexts.keySet())) {
            AiPlaybackContext context = aiContexts.get(runId);
            if (context == null) {
                continue;
            }
            SimulationRun run = simulationRunRepository.findById(runId).orElse(null);
            if (run == null || isTerminated(run.getStatus())) {
                aiContexts.remove(runId);
                continue;
            }
            if (!canAdvanceAiPlan(run.getStatus())) {
                continue;
            }
            if (suspendedAiRunIds.contains(runId)) {
                continue;
            }
            if (context.isHandoverPlanning()) {
                continue;
            }

            try {
                context.advanceClock(tickMillis);
                for (AiPlaybackContext.RobotTimeline robot : context.getRobots()) {
                    advanceAiRobot(context, robot);
                }

                PendingAiPlan pending = pendingAiPlans.get(runId);
                if (pending != null && context.allRobotsHeld()) {
                    try {
                        activatePendingPlan(run, context, pending);
                    } catch (RuntimeException exception) {
                        retryOrRecoverFromActivationFailure(
                                run, context, pending, exception);
                    }
                    continue;
                }

                if (!context.isQuiescing()
                        && context.isFinished() && !context.isTasksFinalized()) {
                    for (AiPlaybackContext.RobotTimeline robot : context.getRobots()) {
                        if (!robot.isFailed()) {
                            robot.setStatus(RobotStatus.IDLE);
                            robot.setCurrentTaskId(null);
                            publishAi(context, robot, null);
                        }
                    }
                    finalizeAiTasks(context);
                    context.markTasksFinalized();
                    aiContexts.remove(runId);
                    log.info("[AI playback] completed runId={}, planId={}, simTimeMs={}",
                            runId, context.getPlanId(), context.getClockMillis());
                }
            } catch (RuntimeException exception) {
                suspendedAiRunIds.add(runId);
                log.error(
                        "[AI playback] run suspended after tick failure: runId={}, planId={}, simTimeMs={}",
                        runId,
                        context.getPlanId(),
                        context.getClockMillis(),
                        exception
                );
            }
        }
    }

    private void advanceAiRobot(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot
    ) {
        synchronized (robot) {
            advanceAiRobotLocked(context, robot);
        }
    }

    private void advanceAiRobotLocked(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot
    ) {
        if (robot.isFailed()) {
            return;
        }
        if (robot.isHeld()) {
            publishAi(context, robot, null);
            return;
        }
        int guard = 0;
        while (!robot.isFinished() && guard++ < MAX_STEPS_PER_TICK) {
            if (context.isQuiescing() && robot.shouldHold(context.getClockMillis())) {
                robot.hold(context.getClockMillis());
                publishAi(context, robot, null);
                return;
            }
            if (robot.needsLowBatteryReplan(context.getChargingThreshold())) {
                robot.holdForLowBattery(context.getClockMillis());
                lowBatteryReplanRequests.computeIfAbsent(
                        context.getSimulationRunId(),
                        ignored -> new LowBatteryReplanRequest(
                                context.getSimulationRunId(),
                                robot.getRobotId(),
                                robot.getBatteryLevel(),
                                context.getChargingThreshold(),
                                robot.getCurrentNodeId(),
                                nodeCodeCache.get(robot.getCurrentNodeId()),
                                robot.getCurrentTaskId(),
                                robot.isCarryingLoad(),
                                context.getClockMillis()
                        )
                );
                publishAi(context, robot, null);
                return;
            }
            AiPlaybackContext.TimedStep step = robot.currentStep();
            if (step == null) {
                return;
            }
            if (context.getClockMillis() < step.startAtMillis()) {
                robot.setStatus(step.taskId() == null ? RobotStatus.IDLE : RobotStatus.ASSIGNED);
                robot.setCurrentTaskId(step.taskId());
                publishAi(context, robot, null);
                return;
            }
            if (!robot.isStepStarted()) {
                startAiStep(context, robot, step);
                robot.setStepStarted(true);
            }
            if (isChargeService(step)) {
                robot.chargeUntil(
                        context.getClockMillis(),
                        context.chargingPowerAt(step.nodeId())
                );
                if (!robot.isFullyCharged()
                        || context.getClockMillis() < step.endAtMillis()) {
                    publishAi(context, robot, step);
                    return;
                }
            }
            if (context.getClockMillis() < step.endAtMillis()) {
                publishAi(context, robot, step);
                return;
            }
            if (!completeAiStep(context, robot, step)) {
                return;
            }
            robot.advanceStep();
        }
        if (context.isQuiescing() && robot.shouldHold(context.getClockMillis())) {
            robot.hold(context.getClockMillis());
            publishAi(context, robot, null);
            return;
        }
        if (robot.isFinished()) {
            robot.setStatus(RobotStatus.IDLE);
            if (!context.isQuiescing()) {
                robot.setCurrentTaskId(null);
            } else {
                robot.hold(context.getClockMillis());
            }
            publishAi(context, robot, null);
        }
    }

    private boolean canAdvanceAiPlan(SimulationRunStatus status) {
        return status == SimulationRunStatus.RUNNING
                || status == SimulationRunStatus.QUIESCING
                || status == SimulationRunStatus.REPLANNING
                || status == SimulationRunStatus.PENDING_ACTIVATION;
    }

    private void activatePendingPlan(
            SimulationRun run,
            AiPlaybackContext oldContext,
            PendingAiPlan pending
    ) {
        AiPlaybackContext next = pending.context();
        if (oldContext.getClockMillis() < next.getClockMillis()) {
            return;
        }
        validateActivationState(oldContext.getSimulationRunId(), oldContext, pending);
        AiPlaybackContext activated = next.rebaseForActivation(
                oldContext.getClockMillis(), oldContext);
        finalizeSupersededTasks(oldContext, activated);
        assignPlannedTasks(pending.assignedRobotByTask());
        pendingAiPlans.remove(oldContext.getSimulationRunId());
        lowBatteryReplanRequests.remove(oldContext.getSimulationRunId());
        lowBatteryInjectionRunIds.remove(oldContext.getSimulationRunId());
        aiContexts.put(oldContext.getSimulationRunId(), activated);
        run.finishReplanning();
        markPlanActivated(activated.getSimulationRunId(), activated.getPlanId());
        inventoryReservationService.releaseSupersededPlan(
                activated.getSimulationRunId(), activated.getPlanId());
        messagingTemplate.convertAndSend(RUN_TOPIC, SimulationRunResponse.from(run));
        log.info("[AI playback] activated replan runId={}, oldPlanId={}, newPlanId={}, simTimeMs={}",
                oldContext.getSimulationRunId(), oldContext.getPlanId(),
                activated.getPlanId(), activated.getClockMillis());
    }

    private void validateActivationState(
            Long simulationRunId,
            AiPlaybackContext oldContext,
            PendingAiPlan pending
    ) {
        Map<Long, RobotState> actualByRobot = simulationRunStateStore.findAll(simulationRunId)
                .stream()
                .collect(Collectors.toMap(RobotState::robotId, value -> value));
        ActivationStateMismatch mismatch = findActivationStateMismatch(
                oldContext,
                pending.context(),
                actualByRobot,
                pending.registeredNodeByRobot()
        );
        if (mismatch != null) {
            log.warn(
                    "[AI playback] activation state mismatch: runId={}, oldPlanId={}, "
                            + "newPlanId={}, robotId={}, reason={}, expectedNodeId={}, "
                            + "runtimeNodeId={}, publishedNodeId={}, publishedNextNodeId={}, "
                            + "runtimeHeld={}, attempt={}/{}",
                    simulationRunId,
                    oldContext.getPlanId(),
                    pending.context().getPlanId(),
                    mismatch.robotId(),
                    mismatch.reason(),
                    mismatch.expectedNodeId(),
                    mismatch.runtimeNodeId(),
                    mismatch.publishedNodeId(),
                    mismatch.publishedNextNodeId(),
                    mismatch.runtimeHeld(),
                    pending.activationAttempts() + 1,
                    MAX_PLAN_ACTIVATION_ATTEMPTS
            );
            throw new BusinessException(ErrorCode.LARO_PLAN_MAPPING_FAILED);
        }
    }

    static ActivationStateMismatch findActivationStateMismatch(
            AiPlaybackContext oldContext,
            AiPlaybackContext nextContext,
            Map<Long, RobotState> actualByRobot,
            Map<Long, Long> registeredNodeByRobot
    ) {
        Map<Long, AiPlaybackContext.RobotTimeline> oldByRobot = oldContext.getRobots()
                .stream()
                .collect(Collectors.toMap(
                        AiPlaybackContext.RobotTimeline::getRobotId,
                        value -> value
                ));
        for (AiPlaybackContext.RobotTimeline nextRobot : nextContext.getRobots()) {
            Long robotId = nextRobot.getRobotId();
            Long expectedNodeId = nextRobot.getCurrentNodeId();
            AiPlaybackContext.RobotTimeline runtime = oldByRobot.get(robotId);
            RobotState published = actualByRobot.get(robotId);

            if (runtime != null) {
                if (!runtime.isHeld()) {
                    return ActivationStateMismatch.of(
                            robotId, "ACTIVE_ROBOT_NOT_HELD", expectedNodeId,
                            runtime, published);
                }
                if (!Objects.equals(expectedNodeId, runtime.getCurrentNodeId())) {
                    return ActivationStateMismatch.of(
                            robotId, "HANDOVER_NODE_MISMATCH", expectedNodeId,
                            runtime, published);
                }
                continue;
            }

            if (published != null) {
                if (published.nextNodeId() != null) {
                    return ActivationStateMismatch.of(
                            robotId, "NEW_ROBOT_STILL_MOVING", expectedNodeId,
                            null, published);
                }
                if (!Objects.equals(expectedNodeId, published.currentNodeId())) {
                    return ActivationStateMismatch.of(
                            robotId, "NEW_ROBOT_NODE_MISMATCH", expectedNodeId,
                            null, published);
                }
                continue;
            }

            Long registeredNodeId = registeredNodeByRobot.get(robotId);
            if (!Objects.equals(expectedNodeId, registeredNodeId)) {
                return new ActivationStateMismatch(
                        robotId,
                        "NEW_ROBOT_STATE_MISSING",
                        expectedNodeId,
                        registeredNodeId,
                        null,
                        null,
                        false
                );
            }
        }
        return null;
    }

    private void finalizeSupersededTasks(
            AiPlaybackContext oldContext,
            AiPlaybackContext nextContext
    ) {
        Set<Long> completedByOldPlan = new HashSet<>(oldContext.getTaskIds());
        completedByOldPlan.removeAll(nextContext.getTaskIds());
        AiPlaybackContext completed = new AiPlaybackContext(
                oldContext.getSimulationRunId(),
                oldContext.getWarehouseId(),
                oldContext.getWarehouseCode(),
                oldContext.getPlanId(),
                oldContext.getPlanVersion(),
                oldContext.getSimulationId(),
                oldContext.getClockMillis(),
                oldContext.getClockMillis(),
                List.of(),
                completedByOldPlan,
                oldContext.getSpeed()
        );
        finalizeAiTasks(completed);
    }

    private void markPlanActivated(Long simulationRunId, String planId) {
        if (planId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "update laro_ext.simulation_plan "
                            + "set status = 'READY', activated_at = now() "
                            + "where plan_id = ? and simulation_run_id = ?",
                    planId,
                    simulationRunId
            );
            jdbcTemplate.update(
                    "update laro_ext.simulation_plan "
                            + "set status = 'SUPERSEDED' "
                            + "where plan_id = (select base_plan_id from laro_ext.simulation_plan "
                            + "where plan_id = ? and simulation_run_id = ?) "
                            + "and simulation_run_id = ?",
                    planId,
                    simulationRunId,
                    simulationRunId
            );
        } catch (RuntimeException exception) {
            log.warn("[AI playback] activation persistence failed: planId={}, reason={}",
                    planId, exception.getMessage());
        }
    }

    private void retryOrRecoverFromActivationFailure(
            SimulationRun run,
            AiPlaybackContext oldContext,
            PendingAiPlan pending,
            RuntimeException exception
    ) {
        int attempts = pending.activationAttempts() + 1;
        if (attempts < MAX_PLAN_ACTIVATION_ATTEMPTS) {
            PendingAiPlan retry = pending.withActivationAttempts(attempts);
            if (pendingAiPlans.replace(oldContext.getSimulationRunId(), pending, retry)) {
                log.warn(
                        "[AI playback] replan activation retry scheduled: "
                                + "runId={}, planId={}, attempt={}/{}, reason={}",
                        oldContext.getSimulationRunId(),
                        pending.context().getPlanId(),
                        attempts,
                        MAX_PLAN_ACTIVATION_ATTEMPTS,
                        exception.getMessage()
                );
                return;
            }
        }
        recoverFromActivationFailure(run, oldContext, pending, exception);
    }

    private void recoverFromActivationFailure(
            SimulationRun run,
            AiPlaybackContext oldContext,
            PendingAiPlan pending,
            RuntimeException exception
    ) {
        pendingAiPlans.remove(oldContext.getSimulationRunId());
        suspendedAiRunIds.add(oldContext.getSimulationRunId());
        if (run.getStatus() == SimulationRunStatus.PENDING_ACTIVATION
                || run.getStatus() == SimulationRunStatus.REPLANNING
                || run.getStatus() == SimulationRunStatus.QUIESCING) {
            run.pauseForHumanReview(LocalDateTime.now());
            messagingTemplate.convertAndSend(RUN_TOPIC, SimulationRunResponse.from(run));
        }
        try {
            jdbcTemplate.update(
                    "update laro_ext.simulation_plan set status = 'FAILED' "
                            + "where plan_id = ? and simulation_run_id = ?",
                    pending.context().getPlanId(),
                    oldContext.getSimulationRunId()
            );
        } catch (RuntimeException persistenceException) {
            log.warn("[AI playback] failed replan status persistence failed: planId={}, reason={}",
                    pending.context().getPlanId(), persistenceException.getMessage());
        }
        inventoryReservationService.releaseActiveForPlan(
                oldContext.getSimulationRunId(), pending.context().getPlanId());
        log.error("[AI playback] pending plan discarded; run paused with old plan held: "
                        + "runId={}, oldPlanId={}, failedPlanId={}, attempts={}",
                oldContext.getSimulationRunId(),
                oldContext.getPlanId(),
                pending.context().getPlanId(),
                pending.activationAttempts() + 1,
                exception);
    }

    private void startAiStep(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot,
            AiPlaybackContext.TimedStep step
    ) {
        robot.setCurrentTaskId(step.taskId());
        switch (step.type()) {
            case MOVE -> {
                robot.setCurrentNodeId(step.fromNodeId());
                robot.setStatus(RobotStatus.MOVING);
                robot.consumeMoveBattery();
            }
            case WAIT -> {
                robot.setCurrentNodeId(step.nodeId());
                robot.setStatus(step.taskId() == null ? RobotStatus.IDLE : RobotStatus.ASSIGNED);
            }
            case SERVICE -> {
                robot.setCurrentNodeId(step.nodeId());
                robot.setStatus(serviceStatus(step.serviceKind(), step.taskId()));
                if (isChargeService(step)) {
                    robot.beginCharging(step.startAtMillis());
                } else {
                    robot.consumeWorkBattery();
                    startTask(step.taskId());
                }
            }
        }
    }

    private boolean completeAiStep(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot,
            AiPlaybackContext.TimedStep step
    ) {
        if (step.type() == AiPlaybackContext.StepType.MOVE) {
            robot.setCurrentNodeId(step.toNodeId());
        } else if (step.nodeId() != null) {
            robot.setCurrentNodeId(step.nodeId());
        }
        if (step.type() == AiPlaybackContext.StepType.SERVICE) {
            String kind = step.serviceKind() == null
                    ? ""
                    : step.serviceKind().toUpperCase(Locale.ROOT);
            if (step.taskId() != null) {
                try {
                    taskService.applyInventoryAtServiceCompletion(step.taskId(), kind);
                } catch (RuntimeException exception) {
                    log.error(
                            "[AI playback] task isolated after rack inventory update failure: "
                                    + "runId={}, planId={}, robotId={}, taskId={}, serviceKind={}, reason={}",
                            context.getSimulationRunId(),
                            context.getPlanId(),
                            robot.getRobotId(),
                            step.taskId(),
                            kind,
                            exception.getMessage(),
                            exception
                    );
                    robot.setFailed(true);
                    robot.setStatus(RobotStatus.ERROR);
                    publishAi(context, robot, null);
                    failAiRobotTasks(context, robot.getRobotId());
                    return false;
                }
            }
            if (isChargeService(step)) {
                robot.finishCharging();
            }
            robot.setCarryingLoad(carryingLoadAfterServiceCompletion(
                    robot.isCarryingLoad(),
                    kind
            ));
            if (robot.completesBeTaskAt(step)) {
                completeAiTaskAtPhysicalBoundary(
                        context,
                        robot,
                        step.taskId(),
                        kind
                );
            }
        }
        return true;
    }

    private void completeAiTaskAtPhysicalBoundary(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot,
            Long taskId,
            String serviceKind
    ) {
        if (taskId == null) {
            return;
        }
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() == TaskStatus.DONE
                || task.getStatus() == TaskStatus.FAILED
                || task.getStatus() == TaskStatus.CANCELLED) {
            return;
        }
        try {
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.start();
                broadcastTask(task);
            }
            if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                taskService.completeTask(taskId);
                log.info(
                        "[AI playback] task completed at physical boundary: "
                                + "runId={}, planId={}, robotId={}, taskId={}, serviceKind={}",
                        context.getSimulationRunId(),
                        context.getPlanId(),
                        robot.getRobotId(),
                        taskId,
                        serviceKind
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[AI playback] task completion at physical boundary failed: "
                            + "runId={}, planId={}, robotId={}, taskId={}, serviceKind={}, reason={}",
                    context.getSimulationRunId(),
                    context.getPlanId(),
                    robot.getRobotId(),
                    taskId,
                    serviceKind,
                    exception.getMessage(),
                    exception
            );
        }
    }

    private boolean isChargeService(AiPlaybackContext.TimedStep step) {
        return step != null
                && step.type() == AiPlaybackContext.StepType.SERVICE
                && "CHARGE".equalsIgnoreCase(step.serviceKind());
    }

    static boolean carryingLoadAfterServiceCompletion(boolean carryingLoad, String serviceKind) {
        return switch (serviceKind == null ? "" : serviceKind.toUpperCase(Locale.ROOT)) {
            case "PICKUP" -> true;
            case "DROP", "STATION" -> false;
            default -> carryingLoad;
        };
    }

    private RobotStatus serviceStatus(String serviceKind, Long taskId) {
        String kind = serviceKind == null ? "" : serviceKind.toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "PICKUP" -> RobotStatus.PICKING;
            case "DROP" -> taskRepository.findById(taskId == null ? -1L : taskId)
                    .map(task -> task.getTaskType() == TaskType.INBOUND
                            ? RobotStatus.PUTAWAY
                            : RobotStatus.RELOCATION)
                    .orElse(RobotStatus.WORKING);
            case "CHARGE" -> RobotStatus.CHARGING;
            case "RETURN", "EMPTY_TOTE_BUFFER", "PARK" -> RobotStatus.RELOCATION;
            default -> RobotStatus.WORKING;
        };
    }

    private void finalizeAiTasks(AiPlaybackContext context) {
        for (Long taskId : context.getTaskIds()) {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task == null || task.getStatus() == TaskStatus.DONE
                    || task.getStatus() == TaskStatus.FAILED
                    || task.getStatus() == TaskStatus.CANCELLED) {
                continue;
            }
            try {
                if (task.getStatus() == TaskStatus.ASSIGNED) {
                    task.start();
                    broadcastTask(task);
                }
                if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                    taskService.completeTask(taskId);
                }
            } catch (RuntimeException exception) {
                log.warn("[AI playback] task completion failed: taskId={}, reason={}",
                        taskId, exception.getMessage());
            }
        }
    }

    private void publishAi(
            AiPlaybackContext context,
            AiPlaybackContext.RobotTimeline robot,
            AiPlaybackContext.TimedStep activeStep
    ) {
        Long currentNodeId = robot.getCurrentNodeId();
        Long nextNodeId = null;
        Double arrivalInSeconds = null;
        String movementStepId = null;
        Long movementStartAtMillis = null;
        Long movementEndAtMillis = null;
        Double movementProgress = null;
        if (activeStep != null && activeStep.type() == AiPlaybackContext.StepType.MOVE) {
            currentNodeId = activeStep.fromNodeId();
            nextNodeId = activeStep.toNodeId();
            long remainingMillis = Math.max(0, activeStep.endAtMillis() - context.getClockMillis());
            arrivalInSeconds = remainingMillis / 1000.0 / context.getSpeed();
            movementStepId = activeStep.stepId();
            movementStartAtMillis = activeStep.startAtMillis();
            movementEndAtMillis = activeStep.endAtMillis();
            movementProgress = movementProgress(
                    context.getClockMillis(),
                    movementStartAtMillis,
                    movementEndAtMillis
            );
        }

        TaskType taskType = taskRepository.findById(
                        robot.getCurrentTaskId() == null ? -1L : robot.getCurrentTaskId())
                .map(Task::getTaskType)
                .orElse(null);
        String serviceKind = activeStep != null
                && activeStep.type() == AiPlaybackContext.StepType.SERVICE
                ? activeStep.serviceKind()
                : null;
        Double serviceProgress = null;
        if (activeStep != null && activeStep.type() == AiPlaybackContext.StepType.SERVICE) {
            long durationMillis = Math.max(
                    1L,
                    activeStep.endAtMillis() - activeStep.startAtMillis()
            );
            serviceProgress = Math.max(
                    0.0,
                    Math.min(
                            1.0,
                            (context.getClockMillis() - activeStep.startAtMillis())
                                    / (double) durationMillis
                    )
            );
        }
        boolean lowBatteryWaiting = robot.isLowBatteryHold();
        boolean quiesceHeld = context.isQuiescing() && robot.isHeld();
        boolean returningToCharge = robot.isReturningToCharge(
                context.getChargingThreshold()
        );
        boolean waiting = lowBatteryWaiting || quiesceHeld || activeStep != null
                && activeStep.type() == AiPlaybackContext.StepType.WAIT;
        RobotStatus visualActivity = visualActivity(robot, activeStep, taskType);
        RobotStatus activity = visualActivity == RobotStatus.CHARGING
                ? RobotStatus.CHARGING
                : lowBatteryWaiting
                        ? RobotStatus.LOW_BATTERY
                        : returningToCharge
                                ? RobotStatus.RETURNING_TO_CHARGE
                                : robot.hasLowBatteryAlert()
                                        ? RobotStatus.LOW_BATTERY
                                        : waiting
                                                ? RobotStatus.WAITING
                                                : visualActivity;
        Long waitingNodeId = lowBatteryWaiting
                ? currentNodeId
                : quiesceHeld ? currentNodeId
                : waiting ? robot.nextMovementTargetNodeId() : null;
        String waitingReason = lowBatteryWaiting
                ? "배터리 " + robot.getBatteryLevel()
                        + "% · 충전 기준 " + context.getChargingThreshold()
                        + "% 도달 · Rule 재계획 요청 중"
                : quiesceHeld ? "재계획 안전 노드에서 대기 중"
                : waiting ? userFacingWaitReason(activeStep.reason()) : null;
        Long waitStartedAtMillis = null;
        Long estimatedResumeAtMillis = null;
        if (lowBatteryWaiting) {
            waitStartedAtMillis = robot.getHeldAtMillis() == null
                    ? robot.getLowBatteryWaitStartedAtMillis()
                    : robot.getHeldAtMillis();
        } else if (quiesceHeld) {
            waitStartedAtMillis = robot.getHeldAtMillis();
        } else if (waiting) {
            waitStartedAtMillis = activeStep.startAtMillis();
            estimatedResumeAtMillis = activeStep.endAtMillis();
        }

        RobotState state = new RobotState(
                robot.getRobotId(),
                context.getWarehouseId(),
                currentNodeId,
                nodeCodeCache.get(currentNodeId),
                nextNodeId,
                nextNodeId == null ? null : nodeCodeCache.get(nextNodeId),
                arrivalInSeconds,
                movementStepId,
                movementStartAtMillis,
                movementEndAtMillis,
                context.getClockMillis(),
                movementProgress,
                robot.getBatteryLevel(),
                robot.getStatus(),
                robot.getCurrentTaskId(),
                taskType == null ? null : taskType.name(),
                activity,
                serviceKind,
                serviceProgress,
                robot.isCarryingLoad(),
                waitingReason,
                waitingNodeId == null ? null : nodeCodeCache.get(waitingNodeId),
                null,
                waitStartedAtMillis,
                estimatedResumeAtMillis,
                LocalDateTime.now()
        );
        simulationRunStateStore.save(context.getSimulationRunId(), state);
        messagingTemplate.convertAndSend(
                robotTopic(context.getSimulationRunId()),
                RobotStateResponse.from(state)
        );
    }

    private double movementProgress(long nowMillis, long startMillis, long endMillis) {
        long durationMillis = Math.max(1L, endMillis - startMillis);
        return Math.max(
                0.0,
                Math.min(1.0, (nowMillis - startMillis) / (double) durationMillis)
        );
    }

    static double simulationBatteryRate(Double configuredRate) {
        if (configuredRate == null || configuredRate <= 0) {
            return 0.0;
        }
        return configuredRate * SIMULATION_BATTERY_RATE_MULTIPLIER;
    }

    private String userFacingWaitReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "통행 예약 순서를 기다리는 중";
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        if (normalized.contains("service node")) {
            return "작업 위치 사용 순서를 기다리는 중";
        }
        if (normalized.contains("safe interval")
                || normalized.contains("occupied")
                || normalized.contains("reserved")) {
            return "통행 예약 순서를 기다리는 중";
        }
        return reason;
    }

    private RobotStatus visualActivity(
            AiPlaybackContext.RobotTimeline robot,
            AiPlaybackContext.TimedStep activeStep,
            TaskType taskType
    ) {
        if (activeStep != null && activeStep.type() == AiPlaybackContext.StepType.SERVICE) {
            return robot.getStatus();
        }
        if (!robot.isCarryingLoad()) {
            return robot.getStatus();
        }
        if (taskType == TaskType.INBOUND) {
            return RobotStatus.PUTAWAY;
        }
        if (taskType == TaskType.OUTBOUND) {
            return RobotStatus.RELOCATION;
        }
        return robot.getStatus();
    }

    private void advance(PlaybackContext context, long tickMillis) {
        if (context.isReplanRequested()
                && context.areAllRobotsStoppedForReplanning()) {
            return;
        }

        long simulatedMillis = context.advanceClock(tickMillis);

        if (context.getReplanningState()
                == PlaybackContext.ReplanningState.ACTIVE
                && context.getActivatedReplanId() != null) {
            advanceReoptimizationPlan(context);
            return;
        }

        // 1) 발생 시각이 된 작업 투입
        for (Long taskId : context.releaseDueTasks()) {
            log.info("[재생] 시뮬 {}초 - 작업 {} 발생",
                    context.clockSeconds(), taskId);
        }

        // 2) 로봇별 진행
        for (RobotRuntime robot : context.getRobots()) {
            step(context, robot, simulatedMillis);
        }
    }

    private void step(
            PlaybackContext context,
            RobotRuntime robot,
            long simulatedMillis
    ) {
        if (robot.getStatus() == RobotStatus.ERROR
                || robot.getStatus() == RobotStatus.OFFLINE) {
            return;
        }

        if (pauseIfReadyForReplanning(context, robot)) {
            return;
        }
        if (robot.getPhase() == RobotRuntime.Phase.CHARGING) {
            charge(context, robot, simulatedMillis);
            return;
        }

        int guard = 0;

        while (context.getClockMillis() >= robot.getBusyUntilMillis()
                && robot.getPhase() != RobotRuntime.Phase.CHARGING
                && guard++ < MAX_STEPS_PER_TICK) {

            long busyBefore = robot.getBusyUntilMillis();
            RobotRuntime.Phase phaseBefore = robot.getPhase();

            try {
                switch (robot.getPhase()) {
                    case IDLE -> tryStartNextTask(context, robot);
                    case MOVING_TO_START -> moveOrArrive(context, robot, true);
                    case PICKING -> beginMoveToEnd(context, robot);
                    case MOVING_TO_END -> moveOrArrive(context, robot, false);
                    case DROPPING -> finishTask(context, robot);
                    case CHARGING -> charge(context, robot, simulatedMillis);
                }
            } catch (Exception exception) {
                log.warn("[재생] 로봇 {} 처리 실패: {}",
                        robot.getRobotId(), exception.getMessage());
                context.releaseChargingNode(robot.getChargingNodeId());
                robot.clearChargingStation();
                robot.setPhase(RobotRuntime.Phase.IDLE);
                robot.setCurrentTaskId(null);
                return;
            }

            if (robot.getBusyUntilMillis() == busyBefore
                    && robot.getPhase() == phaseBefore) {
                return;
            }
        }
    }

    private boolean pauseIfReadyForReplanning(
            PlaybackContext context,
            RobotRuntime robot
    ) {
        if (!context.isReplanRequested()) {
            return false;
        }

        if (robot.isStoppedForReplanning()) {
            return true;
        }

        switch (robot.getPhase()) {
            case IDLE, CHARGING -> {
                robot.pauseForReplanning();
                publish(context, robot);
                return true;
            }

            case MOVING_TO_START, MOVING_TO_END, PICKING -> {
                if (context.getClockMillis() >= robot.getBusyUntilMillis()) {
                    robot.pauseForReplanning();
                    publish(context, robot);
                    return true;
                }
            }

            case DROPPING -> {
                if (context.getClockMillis() >= robot.getBusyUntilMillis()) {
                    finishTask(context, robot);
                    robot.pauseForReplanning();
                    publish(context, robot);
                    return true;
                }
            }
        }

        return false;
    }

    private void tryStartNextTask(PlaybackContext context, RobotRuntime robot) {
        if (robot.getStatus() == RobotStatus.ERROR) {
            return;
        }

        if (!context.hasReadyTask()) {
            return;
        }

        Long taskId = context.pollReadyTask();
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }

        if (task.getTaskType() == TaskType.CHARGE) {
            Long chargingNodeId = task.getEndNode().getId();
            Double chargingPower = context.getChargingPowerByNode().get(chargingNodeId);

            if (chargingPower == null) {
                if (task.getStatus() == TaskStatus.PENDING) {
                    task.assignRobot(robotRepository.getReferenceById(robot.getRobotId()));
                }
                task.fail();
                broadcastTask(task);
                log.warn("[재생] AI 충전 작업 {} 거부: 노드 {}는 사용 가능한 충전소가 아닙니다.",
                        taskId, chargingNodeId);
                return;
            }

            if (!context.reserveChargingNode(chargingNodeId)) {
                if (task.getStatus() == TaskStatus.PENDING) {
                    task.assignRobot(robotRepository.getReferenceById(robot.getRobotId()));
                }
                task.fail();
                broadcastTask(task);
                log.warn("[재생] AI 충전 작업 {} 거부: 충전소 노드 {}가 이미 점유 중입니다.",
                        taskId, chargingNodeId);
                return;
            }

            robot.assignChargingStation(chargingNodeId, chargingPower);
        }

        // DB에 배정 반영
        if (task.getStatus() == TaskStatus.PENDING) {
            task.assignRobot(robotRepository.getReferenceById(robot.getRobotId()));
            broadcastTask(task);
        }

        List<Long> path = pathToWorkPosition(
                context, robot, task.getStartNode().getId());

        robot.setCurrentTaskId(taskId);
        robot.setPath(path);
        robot.setPhase(RobotRuntime.Phase.MOVING_TO_START);
        robot.setStatus(RobotStatus.ASSIGNED);

        publish(context, robot);

        log.info("[재생] 시뮬 {}초 - 로봇 {} 이 작업 {} 시작 (경로 {}칸)",
                context.clockSeconds(), robot.getRobotId(), taskId, path.size());
    }

    private void charge(
            PlaybackContext context,
            RobotRuntime robot,
            long simulatedMillis
    ) {
        robot.charge(simulatedMillis);

        if (robot.isFullyCharged()) {
            Long taskId = robot.getCurrentTaskId();
            if (taskId != null) {
                try {
                    taskService.completeTask(taskId);
                } catch (Exception exception) {
                    log.warn("[재생] 충전 작업 {} 완료 처리 실패: {}", taskId, exception.getMessage());
                }
            }
            context.releaseChargingNode(robot.getChargingNodeId());
            robot.clearChargingStation();
            robot.setCurrentTaskId(null);
            robot.setPhase(RobotRuntime.Phase.IDLE);
            robot.setStatus(RobotStatus.IDLE);
        }

        publish(context, robot);
    }

    private void moveOrArrive(PlaybackContext context, RobotRuntime robot, boolean towardStart) {
        if (robot.hasRemainingPath()) {
            if (!robot.canMove()) {
                failBatteryDepletedTask(context, robot);
                return;
            }

            Long nextNode = robot.pollNextNode();

            robot.moveTo(nextNode);
            robot.setStatus(RobotStatus.MOVING);
            robot.consumeMoveBattery();
            robot.setMovementStartAtMillis(context.getClockMillis());
            robot.setBusyUntilMillis(
                    context.getClockMillis() + context.getMoveMillisPerNode());

            publish(context, robot);
            return;
        }

        robot.stopMoving();

        if (towardStart) {
            Task task = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
            if (task != null && task.getTaskType() == TaskType.CHARGE) {
                startTask(robot.getCurrentTaskId());
                beginMoveToEnd(context, robot);
                return;
            }

            robot.setPhase(RobotRuntime.Phase.PICKING);
            robot.setStatus(RobotStatus.PICKING);
            robot.consumeWorkBattery();
            robot.setBusyUntilMillis(
                    context.getClockMillis() + context.getPickingMillis());

            startTask(robot.getCurrentTaskId());
            publish(context, robot);
        } else {
            Task task = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
            if (task != null && task.getTaskType() == TaskType.CHARGE) {
                robot.setPhase(RobotRuntime.Phase.CHARGING);
                robot.setStatus(RobotStatus.CHARGING);
                publish(context, robot);
                return;
            }

            RobotStatus dropStatus = task != null && task.getTaskType() == TaskType.INBOUND
                    ? RobotStatus.PUTAWAY
                    : RobotStatus.RELOCATION;

            robot.setPhase(RobotRuntime.Phase.DROPPING);
            robot.setStatus(dropStatus);
            robot.consumeWorkBattery();
            robot.setBusyUntilMillis(
                    context.getClockMillis() + context.getLoadingMillis());

            publish(context, robot);
        }
    }

    private void failBatteryDepletedTask(PlaybackContext context, RobotRuntime robot) {
        Long taskId = robot.getCurrentTaskId();
        if (taskId != null) {
            taskRepository.findById(taskId).ifPresent(task -> {
                if (task.getStatus() == TaskStatus.ASSIGNED
                        || task.getStatus() == TaskStatus.IN_PROGRESS) {
                    task.fail();
                    broadcastTask(task);
                }
            });
        }

        context.releaseChargingNode(robot.getChargingNodeId());
        robot.clearChargingStation();
        robot.setCurrentTaskId(null);
        robot.setPhase(RobotRuntime.Phase.IDLE);
        robot.setStatus(RobotStatus.ERROR);
        robot.stopMoving();

        publish(context, robot);
        log.warn("[재생] 로봇 {} 배터리 방전으로 작업 {} 이동 실패",
                robot.getRobotId(), taskId);
    }

    private void beginMoveToEnd(PlaybackContext context, RobotRuntime robot) {
        Task task = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
        if (task == null) {
            robot.setPhase(RobotRuntime.Phase.IDLE);
            robot.setCurrentTaskId(null);
            return;
        }

        taskService.applyInventoryAtServiceCompletion(task.getId(), "PICKUP");

        List<Long> path = pathToWorkPosition(
                context, robot, task.getEndNode().getId());

        robot.setPath(path);
        robot.setPhase(RobotRuntime.Phase.MOVING_TO_END);

        moveOrArrive(context, robot, false);
    }

    private void finishTask(PlaybackContext context, RobotRuntime robot) {
        Long taskId = robot.getCurrentTaskId();

        if (taskId != null) {
            try {
                taskService.applyInventoryAtServiceCompletion(taskId, "DROP");
                taskService.completeTask(taskId);
                log.info("[재생] 시뮬 {}초 - 작업 {} 완료 (로봇 {})",
                        context.clockSeconds(), taskId, robot.getRobotId());
            } catch (Exception exception) {
                log.warn("[재생] 작업 {} 완료 처리 실패: {}", taskId, exception.getMessage());
            }
        }

        robot.setCurrentTaskId(null);
        robot.setPhase(RobotRuntime.Phase.IDLE);
        robot.setStatus(RobotStatus.IDLE);
        robot.stopMoving();

        publish(context, robot);
    }

    private void startTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.start();
                broadcastTask(task);
            }
        });
    }

    /* =========================================================
       상태 전송
    ========================================================= */

    private void publish(PlaybackContext context, RobotRuntime robot) {
        Long nextNodeId = null;
        String nextNodeCode = null;
        Double arrivalInSeconds = null;
        String movementStepId = null;
        Long movementStartAtMillis = null;
        Long movementEndAtMillis = null;
        Double movementProgress = null;

        if (robot.getStatus() == RobotStatus.MOVING) {
            // 현재 이동이 끝나기까지 남은 시간
            long remainingMillis = robot.getBusyUntilMillis() - context.getClockMillis();

            if (remainingMillis > 0) {
                nextNodeId = robot.getCurrentNodeId();
                nextNodeCode = nodeCodeCache.get(nextNodeId);
                // 배속을 반영한 실제 경과 시간(초)으로 환산
                arrivalInSeconds = remainingMillis / 1000.0 / context.getSpeed();
                movementStartAtMillis = robot.getMovementStartAtMillis();
                movementEndAtMillis = robot.getBusyUntilMillis();
                movementStepId = "legacy-" + robot.getRobotId()
                        + "-" + robot.getPreviousNodeId()
                        + "-" + robot.getCurrentNodeId()
                        + "-" + movementStartAtMillis;
                movementProgress = movementProgress(
                        context.getClockMillis(),
                        movementStartAtMillis,
                        movementEndAtMillis
                );
            }
        }

        RobotState state = new RobotState(
                robot.getRobotId(),
                context.getWarehouseId(),
                robot.getPreviousNodeId() == null
                        ? robot.getCurrentNodeId()
                        : robot.getPreviousNodeId(),
                nodeCodeCache.get(
                        robot.getPreviousNodeId() == null
                                ? robot.getCurrentNodeId()
                                : robot.getPreviousNodeId()),
                nextNodeId,
                nextNodeCode,
                arrivalInSeconds,
                movementStepId,
                movementStartAtMillis,
                movementEndAtMillis,
                context.getClockMillis(),
                movementProgress,
                robot.batteryPercent(),
                robot.getStatus(),
                robot.getCurrentTaskId(),
                null,
                robot.getStatus(),
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );

        simulationRunStateStore.save(context.getSimulationRunId(), state);
        messagingTemplate.convertAndSend(
                robotTopic(context.getSimulationRunId()),
                RobotStateResponse.from(state)
        );
    }

    /* =========================================================
       정리 / 유틸
    ========================================================= */

    public boolean requestReplanningStop(Long simulationRunId) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        synchronized (context) {
            context.requestReplanning();

            for (RobotRuntime robot : context.getRobots()) {
                if (robot.getPhase() == RobotRuntime.Phase.IDLE) {
                    robot.pauseForReplanning();
                    publish(context, robot);
                }
            }

            return true;
        }
    }

    public boolean areAllRobotsStoppedForReplanning(Long simulationRunId) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        synchronized (context) {
            return context.areAllRobotsStoppedForReplanning();
        }
    }

    private void advanceReoptimizationPlan(PlaybackContext context) {
        for (RobotRuntime robot : context.getRobots()) {
            if (robot.getStatus() == RobotStatus.ERROR
                    || robot.getStatus() == RobotStatus.OFFLINE) {
                continue;
            }
            int guard = 0;
            boolean progressed = true;
            while (progressed && guard++ < MAX_STEPS_PER_TICK) {
                progressed = advanceReoptimizationRobot(
                        context,
                        robot
                );
            }
        }
    }

    private boolean advanceReoptimizationRobot(
            PlaybackContext context,
            RobotRuntime robot
    ) {
        RuntimeTaskPlan plan = robot.currentRuntimeTaskPlan();
        if (plan == null) {
            return false;
        }
        long now = context.getClockMillis();
        return switch (robot.getReoptimizationExecutionState()) {
            case WAITING -> beginRuntimeTask(robot, plan, now);
            case MOVING_TO_START -> {
                if (!advanceRuntimePath(
                        context,
                        robot,
                        plan.pathToStart(),
                        RobotRuntime.PlannedPathSegment.TO_START
                )) {
                    yield false;
                }
                if (now < plan.pickingWindow().startTimeMillis()) {
                    robot.setBusyUntilMillis(
                            plan.pickingWindow().startTimeMillis()
                    );
                    yield false;
                }
                startRuntimeTask(context, robot, plan);
                yield true;
            }
            case PICKING -> {
                if (now < plan.pickingWindow().endTimeMillis()) {
                    robot.setBusyUntilMillis(
                            plan.pickingWindow().endTimeMillis()
                    );
                    yield false;
                }
                robot.transitionReoptimizationState(
                        RobotRuntime.ReoptimizationExecutionState.MOVING_TO_END
                );
                robot.setReoptimizationPathCursor(
                        RobotRuntime.PlannedPathSegment.TO_END,
                        0
                );
                robot.setPhase(RobotRuntime.Phase.MOVING_TO_END);
                yield true;
            }
            case MOVING_TO_END -> {
                if (!advanceRuntimePath(
                        context,
                        robot,
                        plan.pathToEnd(),
                        RobotRuntime.PlannedPathSegment.TO_END
                )) {
                    yield false;
                }
                if (now < plan.droppingWindow().startTimeMillis()) {
                    robot.setBusyUntilMillis(
                            plan.droppingWindow().startTimeMillis()
                    );
                    yield false;
                }
                robot.transitionReoptimizationState(
                        RobotRuntime.ReoptimizationExecutionState.DROPPING
                );
                robot.setPhase(RobotRuntime.Phase.DROPPING);
                robot.setStatus(RobotStatus.WORKING);
                robot.setBusyUntilMillis(
                        plan.droppingWindow().endTimeMillis()
                );
                publish(context, robot);
                yield true;
            }
            case DROPPING -> {
                if (now < plan.droppingWindow().endTimeMillis()) {
                    yield false;
                }
                taskService.completeTask(plan.taskId());
                robot.completeCurrentRuntimeTask();
                robot.stopMoving();
                publish(context, robot);
                yield true;
            }
            case COMPLETED -> false;
        };
    }

    private boolean beginRuntimeTask(
            RobotRuntime robot,
            RuntimeTaskPlan plan,
            long now
    ) {
        if (now < plan.estimatedStartTimeMillis()) {
            robot.setBusyUntilMillis(plan.estimatedStartTimeMillis());
            return false;
        }
        if (plan.executionStage()
                == com.aivle.be.optimization.dto.response.TaskPlan
                .ExecutionStage.FULL) {
            robot.transitionReoptimizationState(
                    RobotRuntime.ReoptimizationExecutionState.MOVING_TO_START
            );
            robot.setReoptimizationPathCursor(
                    RobotRuntime.PlannedPathSegment.TO_START,
                    0
            );
            robot.setPhase(RobotRuntime.Phase.MOVING_TO_START);
        } else {
            robot.transitionReoptimizationState(
                    RobotRuntime.ReoptimizationExecutionState.MOVING_TO_END
            );
            robot.setReoptimizationPathCursor(
                    RobotRuntime.PlannedPathSegment.TO_END,
                    0
            );
            robot.setPhase(RobotRuntime.Phase.MOVING_TO_END);
            robot.setCurrentTaskId(plan.taskId());
        }
        return true;
    }

    private void startRuntimeTask(
            PlaybackContext context,
            RobotRuntime robot,
            RuntimeTaskPlan plan
    ) {
        Task task = taskRepository.findById(plan.taskId()).orElseThrow();
        if (task.getStatus() == TaskStatus.ASSIGNED) {
            taskService.startTask(plan.taskId());
        }
        robot.setCurrentTaskId(plan.taskId());
        robot.transitionReoptimizationState(
                RobotRuntime.ReoptimizationExecutionState.PICKING
        );
        robot.setPhase(RobotRuntime.Phase.PICKING);
        robot.setStatus(RobotStatus.PICKING);
        robot.setBusyUntilMillis(plan.pickingWindow().endTimeMillis());
        publish(context, robot);
    }

    private boolean advanceRuntimePath(
            PlaybackContext context,
            RobotRuntime robot,
            List<RuntimePathStep> path,
            RobotRuntime.PlannedPathSegment segment
    ) {
        int index = Math.max(0, robot.getCurrentPlanPathStepIndex());
        long now = context.getClockMillis();
        while (index < path.size()) {
            RuntimePathStep current = path.get(index);
            if (index == path.size() - 1) {
                if (now < current.departureTimeMillis()) {
                    robot.setBusyUntilMillis(current.departureTimeMillis());
                    return false;
                }
                robot.setCurrentNodeId(current.nodeId());
                robot.stopMoving();
                robot.setReoptimizationPathCursor(segment, path.size());
                publish(context, robot);
                return true;
            }

            RuntimePathStep next = path.get(index + 1);
            if (now < current.departureTimeMillis()) {
                robot.setCurrentNodeId(current.nodeId());
                robot.stopMoving();
                robot.setBusyUntilMillis(current.departureTimeMillis());
                return false;
            }
            if (current.nodeId().equals(next.nodeId())) {
                if (now < next.arrivalTimeMillis()) {
                    robot.setBusyUntilMillis(next.arrivalTimeMillis());
                    return false;
                }
            } else if (now < next.arrivalTimeMillis()) {
                if (robot.getPreviousNodeId() == null) {
                    robot.setCurrentNodeId(current.nodeId());
                    robot.moveTo(next.nodeId());
                    robot.consumeMoveBattery();
                    robot.setStatus(RobotStatus.MOVING);
                    publish(context, robot);
                }
                robot.setBusyUntilMillis(next.arrivalTimeMillis());
                return false;
            } else {
                if (robot.getPreviousNodeId() == null) {
                    robot.setCurrentNodeId(current.nodeId());
                    robot.moveTo(next.nodeId());
                    robot.consumeMoveBattery();
                }
                robot.stopMoving();
            }
            index++;
            robot.setCurrentNodeId(next.nodeId());
            robot.setReoptimizationPathCursor(segment, index);
        }
        return true;
    }

    public ReplanningSnapshot captureReplanningSnapshot(
            Long simulationRunId
    ) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return null;
        }

        synchronized (context) {
            return context.captureReplanningSnapshot();
        }
    }

    public boolean bindReplanId(
            Long simulationRunId,
            Long snapshotVersion,
            String replanId
    ) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        synchronized (context) {
            return context.bindReplanId(snapshotVersion, replanId);
        }
    }

    public RuntimeReoptimizationPlan installReoptimizationPlan(
            Long simulationRunId,
            ReoptimizationActivationPlan activationPlan
    ) {
        if (activationPlan == null
                || activationPlan.status()
                != ReoptimizationPlanStage.Status.DB_APPLIED) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
            );
        }

        RuntimeReoptimizationPlan runtimePlan;
        try {
            runtimePlan = RuntimeReoptimizationPlan.from(activationPlan);
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED,
                    exception
            );
        }
        PlaybackContext context = contexts.get(simulationRunId);
        if (context == null) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_CONTEXT_NOT_FOUND
            );
        }

        synchronized (context) {
            return context.installReoptimizationPlan(runtimePlan);
        }
    }

    public boolean activateInstalledReoptimizationPlan(
            Long simulationRunId,
            String replanId
    ) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        synchronized (context) {
            context.finishReplanning(replanId);

            for (RobotRuntime robot : context.getRobots()) {
                if (robot.getStatus() != RobotStatus.ERROR
                        && robot.getStatus() != RobotStatus.OFFLINE) {
                    publish(context, robot);
                }
            }

            return true;
        }
    }

    public boolean recoverActivatedReoptimizationPlan(
            Long simulationRunId,
            String replanId
    ) {
        return activateInstalledReoptimizationPlan(
                simulationRunId,
                replanId
        );
    }

    public boolean finishReplanning(Long simulationRunId, String replanId) {
        return activateInstalledReoptimizationPlan(simulationRunId, replanId);
    }

    public void clear(Long simulationRunId) {
        contexts.remove(simulationRunId);
        aiContexts.remove(simulationRunId);
        pendingAiPlans.remove(simulationRunId);
        lowBatteryReplanRequests.remove(simulationRunId);
        lowBatteryInjectionRunIds.remove(simulationRunId);
        suspendedAiRunIds.remove(simulationRunId);
    }

    public boolean markRobotError(Long simulationRunId, Long robotId) {
        AiPlaybackContext aiContext = aiContexts.get(simulationRunId);
        if (aiContext != null && robotId != null) {
            for (AiPlaybackContext.RobotTimeline robot : aiContext.getRobots()) {
                if (!robotId.equals(robot.getRobotId())) {
                    continue;
                }
                robot.setFailed(true);
                robot.setStatus(RobotStatus.ERROR);
                publishAi(aiContext, robot, null);
                failAiRobotTasks(aiContext, robotId);
                return true;
            }
        }
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null || robotId == null) {
            return false;
        }

        synchronized (context) {
            for (RobotRuntime robot : context.getRobots()) {
                if (!robotId.equals(robot.getRobotId())) {
                    continue;
                }

                robot.setStatus(RobotStatus.ERROR);
                robot.stopMoving();

                publish(context, robot);

                log.info(
                        "[재생] runId={} 로봇 {} 고장 처리",
                        simulationRunId,
                        robotId
                );

                return true;
            }

            return false;
        }
    }

    public boolean changeSpeed(Long simulationRunId, double newSpeed) {
        AiPlaybackContext aiContext = aiContexts.get(simulationRunId);
        if (aiContext != null) {
            aiContext.changeSpeed(newSpeed);
            for (AiPlaybackContext.RobotTimeline robot : aiContext.getRobots()) {
                publishAi(aiContext, robot, robot.currentStep());
            }
            return true;
        }
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        context.changeSpeed(newSpeed);

        for (RobotRuntime robot : context.getRobots()) {
            publish(context, robot);
        }

        log.info("[재생] runId={} 배속 변경 -> {}x", simulationRunId, newSpeed);
        return true;
    }

    public boolean isPlaying(Long simulationRunId) {
        return aiContexts.containsKey(simulationRunId)
                || contexts.containsKey(simulationRunId);
    }

    public boolean hasActiveAiPlan(Long simulationRunId) {
        return aiContexts.containsKey(simulationRunId);
    }

    public LowBatteryInjection injectRandomActiveRobotLowBattery(
            Long simulationRunId,
            int requestedBatteryLevel
    ) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        if (context == null
                || context.isQuiescing()
                || pendingAiPlans.containsKey(simulationRunId)
                || lowBatteryReplanRequests.containsKey(simulationRunId)
                || !lowBatteryInjectionRunIds.add(simulationRunId)) {
            throw new BusinessException(ErrorCode.LOW_BATTERY_EVENT_NOT_AVAILABLE);
        }

        int batteryLevel = Math.max(0, Math.min(100, requestedBatteryLevel));
        List<AiPlaybackContext.RobotTimeline> candidates = new ArrayList<>(
                context.getRobots().stream()
                        .filter(robot -> isLowBatteryInjectionCandidate(robot, batteryLevel))
                        .toList()
        );
        while (!candidates.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(candidates.size());
            AiPlaybackContext.RobotTimeline robot = candidates.remove(index);
            synchronized (robot) {
                if (!isLowBatteryInjectionCandidate(robot, batteryLevel)) {
                    continue;
                }
                int previousBatteryLevel = robot.getBatteryLevel();
                robot.setBatteryLevel(batteryLevel);
                robot.markLowBatteryAlert();
                try {
                    publishAi(
                            context,
                            robot,
                            robot.isStepStarted() ? robot.currentStep() : null
                    );
                } catch (RuntimeException exception) {
                    robot.setBatteryLevel(previousBatteryLevel);
                    robot.clearLowBatteryAlert();
                    lowBatteryInjectionRunIds.remove(simulationRunId);
                    throw exception;
                }
                log.info(
                        "[AI playback] low-battery event injected: runId={}, robotId={}, battery={} -> {}%, threshold={}%, taskId={}",
                        simulationRunId,
                        robot.getRobotId(),
                        previousBatteryLevel,
                        batteryLevel,
                        context.getChargingThreshold(),
                        robot.getCurrentTaskId()
                );
                return new LowBatteryInjection(
                        simulationRunId,
                        robot.getRobotId(),
                        previousBatteryLevel,
                        robot.getBatteryLevel(),
                        context.getChargingThreshold(),
                        robot.getCurrentTaskId(),
                        robot.getStatus(),
                        context.getClockMillis()
                );
            }
        }

        lowBatteryInjectionRunIds.remove(simulationRunId);
        throw new BusinessException(ErrorCode.LOW_BATTERY_EVENT_NOT_AVAILABLE);
    }

    private boolean isLowBatteryInjectionCandidate(
            AiPlaybackContext.RobotTimeline robot,
            int targetBatteryLevel
    ) {
        return !robot.isFailed()
                && !robot.isFinished()
                && !robot.isHeld()
                && !robot.isLowBatteryHold()
                && !robot.isLowBatteryReplanRequested()
                && !isChargeService(robot.currentStep())
                && robot.getCurrentTaskId() != null
                && robot.getBatteryLevel() > targetBatteryLevel;
    }

    public List<LowBatteryReplanRequest> pendingLowBatteryReplanRequests() {
        return List.copyOf(lowBatteryReplanRequests.values());
    }

    public void acknowledgeLowBatteryReplanRequest(
            Long simulationRunId,
            Long robotId
    ) {
        lowBatteryReplanRequests.computeIfPresent(
                simulationRunId,
                (ignored, current) -> Objects.equals(current.robotId(), robotId)
                        ? null
                        : current
        );
    }

    public ActiveAiPlan activeAiPlan(Long simulationRunId) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        if (context == null) {
            throw new BusinessException(ErrorCode.LARO_PLAN_NOT_EXECUTABLE);
        }
        return new ActiveAiPlan(
                context.getPlanId(),
                context.getPlanVersion(),
                context.getWarehouseId(),
                context.getWarehouseCode(),
                context.getSimulationId(),
                context.getClockMillis()
        );
    }

    public record LowBatteryReplanRequest(
            Long simulationRunId,
            Long robotId,
            int batteryLevel,
            int chargingThreshold,
            Long currentNodeId,
            String currentNodeCode,
            Long currentTaskId,
            boolean carryingLoad,
            long stoppedAtSimTimeMs
    ) {}

    public record LowBatteryInjection(
            Long simulationRunId,
            Long robotId,
            int previousBatteryLevel,
            int batteryLevel,
            int chargingThreshold,
            Long currentTaskId,
            RobotStatus robotStatus,
            long simulationClockMillis
    ) {}

    public record ReplanBarrierRobotStatus(
            Long robotId,
            boolean held,
            Long currentNodeId,
            Long currentTaskId,
            String currentStepId,
            String currentStepType,
            Long handoverAtMillis,
            Long handoverNodeId,
            Long heldAtMillis,
            boolean carryingLoad
    ) {}

    public void beginQuiescing(Long simulationRunId) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        if (context == null || pendingAiPlans.containsKey(simulationRunId)) {
            throw new BusinessException(ErrorCode.LARO_PLAN_NOT_EXECUTABLE);
        }
        context.requestQuiesce();
        // requestQuiesce can hold robots that are already between steps. Push
        // those exact nodes/timestamps to Redis before the command thread sees
        // the barrier as ready and calls AI.
        for (AiPlaybackContext.RobotTimeline robot : context.getRobots()) {
            synchronized (robot) {
                if (robot.isHeld()) {
                    publishAi(context, robot, null);
                }
            }
        }
        log.info(
                "[AI playback] replan barrier requested: runId={}, simTimeMs={}, robots={}",
                simulationRunId,
                context.getClockMillis(),
                replanBarrierStatus(simulationRunId)
        );
    }

    public boolean isReadyForReplanRequest(Long simulationRunId) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        return context != null && context.readyForReplanRequest();
    }

    public List<ReplanBarrierRobotStatus> replanBarrierStatus(Long simulationRunId) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        if (context == null) {
            return List.of();
        }
        return context.getRobots().stream()
                .map(robot -> {
                    synchronized (robot) {
                        AiPlaybackContext.TimedStep step = robot.currentStep();
                        return new ReplanBarrierRobotStatus(
                                robot.getRobotId(),
                                robot.isHeld(),
                                robot.getCurrentNodeId(),
                                robot.getCurrentTaskId(),
                                step == null ? null : step.stepId(),
                                step == null ? null : step.type().name(),
                                robot.getHandoverAtMillis(),
                                robot.getHandoverNodeId(),
                                robot.getHeldAtMillis(),
                                robot.isCarryingLoad()
                        );
                    }
                })
                .toList();
    }

    public void cancelQuiescing(Long simulationRunId) {
        AiPlaybackContext context = aiContexts.get(simulationRunId);
        if (context != null) {
            context.cancelQuiesce();
        }
        pendingAiPlans.remove(simulationRunId);
    }

    /** 현재 시뮬레이션 시각(ms). 진행 중이 아니면 0. */
    public long currentClockMillis(Long simulationRunId) {
        AiPlaybackContext aiContext = aiContexts.get(simulationRunId);
        if (aiContext != null) {
            return aiContext.getClockMillis();
        }
        PlaybackContext context = contexts.get(simulationRunId);
        return context == null ? 0 : context.getClockMillis();
    }

    private void failAiRobotTasks(AiPlaybackContext context, Long robotId) {
        AiPlaybackContext.RobotTimeline timeline = context.getRobots().stream()
                .filter(value -> robotId.equals(value.getRobotId()))
                .findFirst()
                .orElse(null);
        if (timeline == null) {
            return;
        }
        Set<Long> robotTaskIds = timeline.getSteps().stream()
                .map(AiPlaybackContext.TimedStep::taskId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (Long taskId : robotTaskIds) {
            taskRepository.findById(taskId).ifPresent(task -> {
                if (task.getStatus() == TaskStatus.ASSIGNED
                        || task.getStatus() == TaskStatus.IN_PROGRESS) {
                    try {
                        taskService.failTask(taskId);
                    } catch (RuntimeException exception) {
                        log.warn("[AI playback] task failure update failed: taskId={}, reason={}",
                                taskId, exception.getMessage());
                    }
                }
            });
        }
    }

    private boolean isTerminated(SimulationRunStatus status) {
        return status == SimulationRunStatus.COMPLETED
                || status == SimulationRunStatus.FAILED
                || status == SimulationRunStatus.STOPPED
                || status == SimulationRunStatus.CREATED;
    }

    public record ActiveAiPlan(
            String planId,
            Integer planVersion,
            Long warehouseNumericId,
            String warehouseCode,
            String simulationId,
            long clockMillis
    ) {}

    private record PreparedAiPlan(
            AiPlaybackContext context,
            Map<Long, Robot> assignedRobotByTask,
            Map<String, Long> robotIdsByAiCode,
            Map<String, Long> nodeIdsByCode,
            Map<Long, Long> registeredNodeByRobot
    ) {}

    record PendingAiPlan(
            AiPlaybackContext context,
            Map<Long, Robot> assignedRobotByTask,
            Map<Long, Long> registeredNodeByRobot,
            int activationAttempts
    ) {
        PendingAiPlan withActivationAttempts(int attempts) {
            return new PendingAiPlan(
                    context, assignedRobotByTask, registeredNodeByRobot, attempts);
        }
    }

    record ActivationStateMismatch(
            Long robotId,
            String reason,
            Long expectedNodeId,
            Long runtimeNodeId,
            Long publishedNodeId,
            Long publishedNextNodeId,
            boolean runtimeHeld
    ) {
        static ActivationStateMismatch of(
                Long robotId,
                String reason,
                Long expectedNodeId,
                AiPlaybackContext.RobotTimeline runtime,
                RobotState published
        ) {
            return new ActivationStateMismatch(
                    robotId,
                    reason,
                    expectedNodeId,
                    runtime == null ? null : runtime.getCurrentNodeId(),
                    published == null ? null : published.currentNodeId(),
                    published == null ? null : published.nextNodeId(),
                    runtime != null && runtime.isHeld()
            );
        }
    }

    private Map<Long, List<Long>> buildAccessNodes(
            Long warehouseId,
            Map<Long, Set<Long>> adjacency
    ) {
        Set<Long> rackNodeIds = warehouseNodeRepository
                .findAllByWarehouse_IdAndNodeTypeAndActiveTrue(
                        warehouseId,
                        NodeType.RACK_STORAGE
                )
                .stream()
                .map(WarehouseNode::getId)
                .collect(Collectors.toSet());

        Map<Long, List<Long>> accessNodes = new HashMap<>();

        for (Map.Entry<Long, Set<Long>> entry : adjacency.entrySet()) {
            Long from = entry.getKey();

            for (Long to : entry.getValue()) {
                if (rackNodeIds.contains(to) && !rackNodeIds.contains(from)) {
                    accessNodes.computeIfAbsent(to, key -> new ArrayList<>()).add(from);
                }
                if (rackNodeIds.contains(from) && !rackNodeIds.contains(to)) {
                    accessNodes.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
                }
            }
        }

        return accessNodes;
    }

    private List<Long> pathToWorkPosition(
            PlaybackContext context,
            RobotRuntime robot,
            Long targetNodeId
    ) {
        List<Long> candidates = context.getAccessNodes().get(targetNodeId);

        if (candidates == null || candidates.isEmpty()) {
            return pathFinder.findPath(
                    context.getAdjacency(), robot.getCurrentNodeId(), targetNodeId);
        }

        if (candidates.contains(robot.getCurrentNodeId())) {
            return List.of();
        }

        List<Long> shortest = null;

        for (Long access : candidates) {
            List<Long> path = pathFinder.findPath(
                    context.getAdjacency(), robot.getCurrentNodeId(), access);

            if (path.isEmpty()) {
                continue;
            }

            if (shortest == null || path.size() < shortest.size()) {
                shortest = path;
            }
        }

        return shortest == null ? List.of() : shortest;
    }

    private void cacheNodeCodes(Long warehouseId) {
        for (WarehouseNode node : warehouseNodeRepository
                .findAllByWarehouse_IdAndActiveTrue(warehouseId)) {
            if (node.getNodeCode() != null) {
                nodeCodeCache.put(node.getId(), node.getNodeCode());
            }
        }
    }

    private void broadcastTask(Task task) {
        messagingTemplate.convertAndSend(TASK_TOPIC, new TaskResponse(task));
    }

    private String robotTopic(Long simulationRunId) {
        return RUN_TOPIC + "/" + simulationRunId + "/robots";
    }

}
