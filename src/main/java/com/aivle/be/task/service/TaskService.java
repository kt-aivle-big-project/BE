package com.aivle.be.task.service;

import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;

    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        // getReferenceById: 실제 SELECT 없이 프록시로 참조만 잡음 (FK만 걸면 되니까 충분)
        Warehouse warehouse = warehouseRepository.getReferenceById(request.getWarehouseId());
        WarehouseNode startNode = warehouseNodeRepository.getReferenceById(request.getStartNodeId());
        WarehouseNode endNode = warehouseNodeRepository.getReferenceById(request.getEndNodeId());

        Task task = new Task();
        task.setWarehouse(warehouse);
        task.setStartNode(startNode);
        task.setEndNode(endNode);
        task.setTaskType(request.getTaskType());
        task.setStatus(Task.TaskStatus.PENDING);
        task.setRequestedAt(LocalDateTime.now());

        Task saved = taskRepository.save(task);
        return new TaskResponse(saved);
    }
}