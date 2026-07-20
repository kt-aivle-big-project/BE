package com.aivle.be.event.service;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulation.controller.response.PathOverlapResponse;
import com.aivle.be.simulation.service.SimulationService;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EventService {

    private static final Set<EventType> PATH_OVERLAP_CHECK_TYPES =
            Set.of(EventType.COLLISION_RISK, EventType.PATH_BLOCKED);

    private final EventRepository eventRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final SimulationService simulationService;

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
        Warehouse warehouse = warehouseRepository.getReferenceById(request.warehouseId());

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

        if (PATH_OVERLAP_CHECK_TYPES.contains(saved.getEventType()) && saved.getNodeId() != null) {
            PathOverlapResponse overlap = simulationService.checkPathOverlap(saved.getNodeId());
            return new EventResponse(saved, overlap.overlapping(), overlap.affectedSimulationIds());
        }

        return new EventResponse(saved);
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
        return new EventResponse(event);
    }

    private Event findEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }
}