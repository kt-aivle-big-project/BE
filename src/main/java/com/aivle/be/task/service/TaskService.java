package com.aivle.be.task.service;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.task.controller.request.TaskAssignRequest;
import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final RobotRepository robotRepository;

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

        // TODO: 로봇이 실제 IDLE 상태인지는 Redis 실시간 상태 붙인 뒤 거기서 확인하도록 교체 예정
        task.assignRobot(robot);

        return new TaskResponse(task);
    }

    @Transactional
    public TaskResponse startTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.start();
        return new TaskResponse(task);
    }

    @Transactional
    public TaskResponse completeTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.complete();
        return new TaskResponse(task);
    }

    @Transactional
    public TaskResponse failTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.fail();
        return new TaskResponse(task);
    }

    @Transactional
    public void cancelTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.cancel();
    }

    private Task findTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }
}