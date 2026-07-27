package com.aivle.be.task.controller.request;

import com.aivle.be.task.entity.TaskType;
import io.swagger.v3.oas.annotations.media.Schema;
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
        Integer quantity,

        @Schema(
                description = "시뮬레이션 시작 후 몇 초에 이 작업이 발생하는지. 없으면 시작과 동시에 발생",
                example = "30",
                nullable = true
        )
        Integer releaseAtSeconds
) {
    public TaskCreateRequest(
            Long warehouseId,
            Long startNodeId,
            Long endNodeId,
            Long warehouseItemId,
            Long itemId,
            TaskType taskType
    ) {
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId,
                taskType, null, null, null);
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
        this(warehouseId, startNodeId, endNodeId, warehouseItemId, itemId,
                taskType, simulationRunId, null, null);
    }
}
