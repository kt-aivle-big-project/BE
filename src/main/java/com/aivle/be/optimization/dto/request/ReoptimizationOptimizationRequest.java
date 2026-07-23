package com.aivle.be.optimization.dto.request;

import com.aivle.be.optimization.domain.ReoptimizationReason;

import java.util.List;

// TODO: AI 팀의 재최적화 API 최종 스펙 확정 후 필드명과 자료형 조정
public record ReoptimizationOptimizationRequest(

        Long simulationRunId,
        Long warehouseId,
        ReoptimizationReason reason,
        Long triggerRobotId,
        List<Long> blockedEdgeIds,
        String description,

        List<RobotStateInput> robots,
        List<TaskInput> remainingTasks
) {

    public record RobotStateInput(
            Long robotId,
            Long currentNodeId,
            Double batteryLevel,
            String status
    ) {
    }

    public record TaskInput(
            Long taskId,
            Long assignedRobotId,
            Long startNodeId,
            Long endNodeId,
            String taskType,
            String status
    ) {
    }
}