package com.aivle.be.task.controller.response;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;

import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        TaskType taskType,
        TaskStatus status,
        Long simulationRunId,
        Long warehouseId,
        Long startNodeId,
        Long endNodeId,
        Long warehouseItemId,
        Long itemId,
        Long robotId,
        Integer quantity,
        LocalDateTime requestedAt,
        LocalDateTime assignedAt
) {
    public TaskResponse(Task task) {
        this(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                task.getSimulationRun() != null ? task.getSimulationRun().getId() : null,
                task.getWarehouse().getId(),
                task.getStartNode().getId(),
                task.getEndNode().getId(),
                task.getWarehouseItem() != null ? task.getWarehouseItem().getId() : null,
                task.getEffectiveItemId(),
                task.getRobot() != null ? task.getRobot().getId() : null,
                task.getQuantity(),
                task.getRequestedAt(),
                task.getAssignedAt()
        );
    }
}
