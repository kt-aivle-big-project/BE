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
        Integer quantity
) {
}
