package com.aivle.be.event.service;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.event.repository.EventRuntimeConstraintStore;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulation.controller.response.PathOverlapResponse;
import com.aivle.be.simulation.service.SimulationService;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Logger log =
            LoggerFactory.getLogger(EventService.class);

    private static final Set<EventType> PATH_OVERLAP_CHECK_TYPES =
            Set.of(EventType.COLLISION_RISK, EventType.PATH_BLOCKED);

    private static final Set<EventType> AUTO_REPLAN_TRIGGERS = Set.of(
            EventType.LOW_BATTERY,
            EventType.PATH_BLOCKED,
            EventType.COLLISION_RISK,
            EventType.TASK_FAILED
    );

    private static final String TOPIC = "/topic/events";

    private final EventRepository eventRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final SimulationService simulationService;
    private final SimulationCommandCycleService commandCycleService;
    private final SimulationPlaybackService playbackService;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final EventRuntimeConstraintStore runtimeConstraintStore;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
        EventTarget target = request.simulationRunId() == null
                ? explicitTarget(request)
                : automaticTarget(request);
        Warehouse warehouse = target.warehouse();
        Robot robot = target.robot();
        Task task = target.task();
        Long nodeId = target.nodeId();

        EventCreateRequest resolvedRequest = new EventCreateRequest(
                request.simulationRunId(),
                warehouse.getId(),
                robot == null ? null : robot.getId(),
                task == null ? null : task.getId(),
                request.eventType(),
                request.description(),
                nodeId
        );
        validateEventScope(resolvedRequest, warehouse, robot, task);

        String description = request.description() == null || request.description().isBlank()
                ? automaticDescription(request.eventType(), robot, task, nodeId)
                : request.description().trim();
        Event event = new Event(warehouse, robot, task, request.eventType(), description, nodeId);
        Event saved = eventRepository.save(event);
        applyOperationalEffect(saved);

        EventResponse response;
        if (PATH_OVERLAP_CHECK_TYPES.contains(saved.getEventType()) && saved.getNodeId() != null) {
            PathOverlapResponse overlap = simulationService.checkPathOverlap(saved.getNodeId());
            response = new EventResponse(saved, overlap.overlapping(), overlap.affectedSimulationIds());
        } else {
            response = new EventResponse(saved);
        }

        messagingTemplate.convertAndSend(TOPIC, response);
        triggerReoptimizationAfterCommitIfNeeded(saved);
        return response;
    }

    private EventTarget explicitTarget(EventCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        Robot robot = request.robotId() != null
                ? robotRepository.findById(request.robotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND))
                : null;
        Task task = request.taskId() != null
                ? taskRepository.findById(request.taskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND))
                : null;
        return new EventTarget(warehouse, robot, task, request.nodeId());
    }

    private EventTarget automaticTarget(EventCreateRequest request) {
        SimulationRun run = simulationRunRepository.findByIdWithWarehouse(request.simulationRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        if (run.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }
        List<Task> activeTasks = taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        run.getId(), List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        if (activeTasks.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return switch (request.eventType()) {
            case LOW_BATTERY -> {
                List<Task> assigned = activeTasks.stream()
                        .filter(value -> value.getRobot() != null)
                        .toList();
                Task task = randomOne(assigned);
                yield new EventTarget(run.getWarehouse(), task.getRobot(), task, null);
            }
            case TASK_FAILED -> {
                Task task = randomOne(activeTasks);
                yield new EventTarget(run.getWarehouse(), task.getRobot(), task, null);
            }
            case PATH_BLOCKED, COLLISION_RISK -> {
                SimulationPlaybackService.FutureRouteTarget routeTarget = playbackService
                        .randomFutureRouteTarget(run.getId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
                Robot robot = robotRepository.findById(routeTarget.robotId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
                Task task = routeTarget.taskId() == null
                        ? activeTasks.stream()
                                .filter(value -> value.getRobot() != null
                                        && robot.getId().equals(value.getRobot().getId()))
                                .findFirst()
                                .orElseGet(() -> randomOne(activeTasks))
                        : taskRepository.findById(routeTarget.taskId())
                                .filter(value -> value.getSimulationRun() != null
                                        && run.getId().equals(value.getSimulationRun().getId()))
                                .orElseGet(() -> randomOne(activeTasks));
                yield new EventTarget(
                        run.getWarehouse(), robot, task, routeTarget.nodeId());
            }
            case REPLAN_TRIGGERED -> new EventTarget(
                    run.getWarehouse(), null, randomOne(activeTasks), null);
        };
    }

    private <T> T randomOne(List<T> values) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private String automaticDescription(
            EventType eventType,
            Robot robot,
            Task task,
            Long nodeId
    ) {
        return switch (eventType) {
            case LOW_BATTERY -> "로봇 R" + robot.getId()
                    + "의 배터리가 충전 임계치 아래로 감소했습니다.";
            case TASK_FAILED -> "작업 #" + task.getId()
                    + " 수행에 실패했습니다.";
            case PATH_BLOCKED -> "노드 #" + nodeId
                    + "에서 경로 차단이 발생했습니다.";
            case COLLISION_RISK -> "노드 #" + nodeId
                    + "에서 충돌 위험이 감지되었습니다.";
            case REPLAN_TRIGGERED -> "자동 재계획이 시작되었습니다.";
        };
    }

    private record EventTarget(Warehouse warehouse, Robot robot, Task task, Long nodeId) {}

    public EventResponse getEvent(Long eventId) {
        return new EventResponse(findEventOrThrow(eventId));
    }

    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(EventResponse::new)
                .toList();
    }

    @Transactional
    public EventResponse resolveEvent(Long eventId) {
        Event event = findEventOrThrow(eventId);
        if (event.getResolvedAt() != null) {
            return new EventResponse(event);
        }
        event.resolve();
        restoreOperationalEffect(event);
        EventResponse response = new EventResponse(event);
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }

    private void triggerReoptimizationAfterCommitIfNeeded(Event event) {
        if (!AUTO_REPLAN_TRIGGERS.contains(event.getEventType())) {
            return;
        }
        if (event.getTask() == null || event.getTask().getSimulationRun() == null) {
            return;
        }

        SimulationRun run = event.getTask().getSimulationRun();
        if (!Boolean.TRUE.equals(run.getAutoReplan())) {
            return;
        }

        Long simulationRunId = run.getId();
        long executionVersion = run.getExecutionVersion();
        String userCommand = eventCommand(event);

        Runnable trigger = () -> {
            boolean quiescingRequested = false;
            try {
                if (playbackService.hasActiveAiPlan(simulationRunId)) {
                    playbackService.beginQuiescing(simulationRunId);
                    quiescingRequested = true;
                }
                commandCycleService.triggerUserCommand(
                        simulationRunId,
                        executionVersion,
                        userCommand
                );
            } catch (BusinessException exception) {
                if (quiescingRequested) {
                    playbackService.cancelQuiescing(simulationRunId);
                }
                log.warn(
                        "이벤트 후 재계획이 정지 상태로 종료됨: runId={}, errorCode={}",
                        simulationRunId,
                        exception.getErrorCode().getCode()
                );
            } catch (RuntimeException exception) {
                if (quiescingRequested) {
                    playbackService.cancelQuiescing(simulationRunId);
                }
                log.error(
                        "이벤트 후 재계획 처리 실패: runId={}",
                        simulationRunId,
                        exception
                );
            }
        };

        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            trigger.run();
                        }
                    }
            );
            return;
        }

        trigger.run();
    }

    @Transactional
    public EventResponse resolveAutomatically(Long eventId) {
        return resolveEvent(eventId);
    }

    private void validateEventScope(
            EventCreateRequest request,
            Warehouse warehouse,
            Robot robot,
            Task task
    ) {
        if (robot != null && !warehouse.getId().equals(robot.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (task != null && !warehouse.getId().equals(task.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (robot != null && task != null && task.getRobot() != null
                && !robot.getId().equals(task.getRobot().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if ((request.eventType() == EventType.PATH_BLOCKED
                || request.eventType() == EventType.COLLISION_RISK)
                && request.nodeId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (request.nodeId() != null) {
            WarehouseNode node = warehouseNodeRepository.findById(request.nodeId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));
            if (!warehouse.getId().equals(node.getWarehouse().getId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }
    }

    private void applyOperationalEffect(Event event) {
        SimulationRun run = event.getTask() == null
                ? null
                : event.getTask().getSimulationRun();
        switch (event.getEventType()) {
            case LOW_BATTERY -> {
                if (run == null || event.getRobot() == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT);
                }
                int lowBattery = run.getChargingThreshold() == null
                        ? 10
                        : Math.max(0, run.getChargingThreshold() - 1);
                if (!playbackService.updateRobotBattery(
                        run.getId(), event.getRobot().getId(), lowBattery)) {
                    throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
                }
            }
            case TASK_FAILED -> {
                if (event.getTask() == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT);
                }
                event.getTask().fail();
            }
            case PATH_BLOCKED -> {
                // The LARO quiescing barrier performs the transient safe stop.
                // A runtime blockage must never mutate the canonical warehouse
                // graph; PostgreSQL and Neo4j map-contract counts must stay equal.
                if (run == null) {
                    throw new BusinessException(ErrorCode.INVALID_INPUT);
                }
                runtimeConstraintStore.blockEdges(
                        run.getId(),
                        event.getId(),
                        warehouseEdgeRepository.findAllByFromNode_IdOrToNode_Id(
                                        event.getNodeId(), event.getNodeId())
                                .stream()
                                .filter(this::isRuntimeBlockableEdge)
                                .toList()
                );
            }
            case COLLISION_RISK, REPLAN_TRIGGERED -> {
                // Transient safety state only; no persistent map mutation.
            }
        }
    }

    private void restoreOperationalEffect(Event event) {
        if (event.getEventType() != EventType.PATH_BLOCKED
                || event.getTask() == null
                || event.getTask().getSimulationRun() == null) {
            return;
        }
        runtimeConstraintStore.releaseEvent(
                event.getTask().getSimulationRun().getId(),
                event.getId()
        );
    }

    private boolean isRuntimeBlockableEdge(
            com.aivle.be.warehouseedge.entity.WarehouseEdge edge
    ) {
        if (Boolean.FALSE.equals(edge.getMobileRobotTraversable())
                || Boolean.TRUE.equals(edge.getServiceOnly())) {
            return false;
        }
        return isTransitRouteNode(edge.getFromNode())
                && isTransitRouteNode(edge.getToNode());
    }

    private boolean isTransitRouteNode(WarehouseNode node) {
        return node != null
                && node.isActive()
                && !Boolean.TRUE.equals(node.getServiceOnly())
                && (node.getNodeType()
                        == com.aivle.be.warehousenode.domain.NodeType.ROUTE
                || node.getNodeType()
                        == com.aivle.be.warehousenode.domain.NodeType.ROUTE_CHARGE_JUNCTION);
    }

    private String eventCommand(Event event) {
        return "현재 Redis에 반영된 로봇 상태와 런타임 경로 제약을 권위값으로 "
                + "사용하여 남은 작업을 자동 재계획해 주세요. 별도의 운영자 확인이 "
                + "필요하지 않으면 즉시 실행 가능한 계획을 생성해 주세요.";
    }

    private Event findEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }
}
