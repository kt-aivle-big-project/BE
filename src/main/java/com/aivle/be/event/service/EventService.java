package com.aivle.be.event.service;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.service.ReoptimizationService;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulation.controller.response.PathOverlapResponse;
import com.aivle.be.simulation.service.SimulationService;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Logger log =
            LoggerFactory.getLogger(EventService.class);

    private static final Set<EventType> PATH_OVERLAP_CHECK_TYPES =
            Set.of(EventType.COLLISION_RISK, EventType.PATH_BLOCKED);

    private static final Map<EventType, ReoptimizationReason> REOPT_TRIGGERS = Map.of(
            EventType.LOW_BATTERY,    ReoptimizationReason.LOW_BATTERY,
            EventType.PATH_BLOCKED,   ReoptimizationReason.OBSTACLE_DETECTED,
            EventType.COLLISION_RISK, ReoptimizationReason.OBSTACLE_DETECTED,
            EventType.TASK_FAILED,    ReoptimizationReason.ROBOT_FAILURE
    );

    private static final String TOPIC = "/topic/events";

    private final EventRepository eventRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final SimulationService simulationService;
    private final ReoptimizationService reoptimizationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
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

        Event event = new Event(warehouse, robot, task, request.eventType(), request.description(), request.nodeId());

        Event saved = eventRepository.save(event);

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
        event.resolve();
        EventResponse response = new EventResponse(event);
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }

    private void triggerReoptimizationAfterCommitIfNeeded(Event event) {
        ReoptimizationReason reason = REOPT_TRIGGERS.get(event.getEventType());
        if (reason == null) {
            return;
        }
        if (event.getTask() == null || event.getTask().getSimulationRun() == null) {
            return;
        }

        Long simulationRunId = event.getTask().getSimulationRun().getId();
        Long triggerRobotId = event.getRobot() == null ? null : event.getRobot().getId();
        ReoptimizationRequest reoptimizationRequest =
                new ReoptimizationRequest(
                        reason,
                        triggerRobotId,
                        List.of(),
                        event.getDescription()
                );

        Runnable trigger = () -> {
            try {
                reoptimizationService.reoptimize(
                        simulationRunId,
                        reoptimizationRequest
                );
            } catch (BusinessException exception) {
                log.warn(
                        "이벤트 후 재계획이 정지 상태로 종료됨: runId={}, errorCode={}",
                        simulationRunId,
                        exception.getErrorCode().getCode()
                );
            } catch (RuntimeException exception) {
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

    private Event findEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }
}
