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
        // 시뮬레이션 시작 후 몇 초에 이 작업이 발생하는지 (null이면 시작과 동시에)
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
