package com.aivle.be.task.service;

import com.aivle.be.task.entity.TaskType;

public record TaskCreateCommand(
        Long warehouseId,
        Long startNodeId,
        Long endNodeId,
        Long warehouseItemId,
        Long itemId,
        TaskType taskType,
        Long simulationRunId,
        Integer quantity,
        Integer releaseAtSeconds,
        String externalOperationId,
        Integer targetRackLevel
) {
    public TaskCreateCommand(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType,
            Long simulationRunId,
            Integer quantity
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId,
                taskType, simulationRunId, quantity, null, null, null);
    }

    public TaskCreateCommand(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType,
            Long simulationRunId,
            Integer quantity,
            Integer releaseAtSeconds
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId,
                taskType, simulationRunId, quantity, releaseAtSeconds, null, null);
    }

    public TaskCreateCommand(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType,
            Long simulationRunId,
            Integer quantity,
            Integer releaseAtSeconds,
            String externalOperationId
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId,
                taskType, simulationRunId, quantity, releaseAtSeconds, externalOperationId, null);
    }
}
