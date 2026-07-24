package com.aivle.be.task.controller.request;

import com.aivle.be.task.entity.TaskType;
import jakarta.validation.constraints.Positive;

public record TaskCreateRequest(
        Long warehouseId,
        Long startNodeId,
        Long endNodeId,
        Long warehouseItemId,
        Long itemId,
        TaskType taskType,
        Long simulationRunId,
        @Positive
        Integer quantity
) {
    public TaskCreateRequest(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId, taskType, null, null);
    }

    public TaskCreateRequest(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType,
            Long simulationRunId
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId, taskType, simulationRunId, null);
    }
}
