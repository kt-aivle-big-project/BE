package com.aivle.be.task.controller.request;

import com.aivle.be.task.entity.TaskType;

public record TaskCreateRequest(
        Long warehouseId,
        Long startNodeId,
        Long endNodeId,
        Long warehouseItemId, // 입출고 작업일 때만 채움, 없으면 null
        TaskType taskType,
        Long simulationRunId
) {
    public TaskCreateRequest(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            TaskType taskType
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, taskType, null);
    }
}
