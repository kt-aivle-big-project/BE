package com.aivle.be.event.service;

import com.aivle.be.event.controller.request.EventCreateRequest;
import com.aivle.be.event.controller.response.EventResponse;
import com.aivle.be.event.entity.Event;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;

    // ===== Create =====

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

        Event event = new Event(warehouse, robot, task, request.eventType(), request.description());

        Event saved = eventRepository.save(event);
        return new EventResponse(saved);
    }

    // ===== Read =====

    public EventResponse getEvent(Long eventId) {
        return new EventResponse(findEventOrThrow(eventId));
    }

    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(EventResponse::new)
                .toList();
    }

    // ===== Update (해결 처리) =====

    @Transactional
    public EventResponse resolveEvent(Long eventId) {
        Event event = findEventOrThrow(eventId);
        event.resolve();
        return new EventResponse(event);
    }

    // ===== 공통 조회 헬퍼 =====

    private Event findEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }
}