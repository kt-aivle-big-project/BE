package com.aivle.be.task.service;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.task.controller.request.TaskAssignRequest;
import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private static final String TOPIC = "/topic/tasks";

    private final TaskRepository taskRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final RobotRepository robotRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        Warehouse warehouse = warehouseRepository.getReferenceById(request.warehouseId());
        WarehouseNode startNode = warehouseNodeRepository.getReferenceById(request.startNodeId());
        WarehouseNode endNode = warehouseNodeRepository.getReferenceById(request.endNodeId());
        WarehouseItem warehouseItem = request.warehouseItemId() != null
                ? warehouseItemRepository.getReferenceById(request.warehouseItemId())
                : null;

        Task task = new Task(warehouse, startNode, endNode, request.taskType(), warehouseItem);

        Task saved = taskRepository.save(task);
        return new TaskResponse(saved);
    }

    public TaskResponse getTask(Long taskId) {
        return new TaskResponse(findTaskOrThrow(taskId));
    }

    public List<TaskResponse> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(TaskResponse::new)
                .toList();
    }

    @Transactional
    public TaskResponse assignRobot(Long taskId, TaskAssignRequest request) {
        Task task = findTaskOrThrow(taskId);

        Robot robot = robotRepository.findById(request.robotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));

        boolean alreadyWorking = taskRepository.existsByRobot_IdAndStatusIn(
                robot.getId(), List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        if (alreadyWorking) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_AVAILABLE);
        }

        task.assignRobot(robot);

        return broadcast(task);
    }

    @Transactional
    public TaskResponse startTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.start();
        return broadcast(task);
    }

    @Transactional
    public TaskResponse completeTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.complete();
        return broadcast(task);
    }

    @Transactional
    public TaskResponse failTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.fail();
        return broadcast(task);
    }

    @Transactional
    public void cancelTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.cancel();
        broadcast(task);
    }

    private TaskResponse broadcast(Task task) {
        TaskResponse response = new TaskResponse(task);
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }

    private Task findTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }
}