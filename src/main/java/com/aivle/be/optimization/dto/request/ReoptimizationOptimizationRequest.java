package com.aivle.be.optimization.dto.request;

import com.aivle.be.optimization.domain.ReoptimizationReason;

import java.util.List;

// TODO: AI 팀의 재최적화 API 최종 스펙 확정 후 필드명과 자료형 조정
public record ReoptimizationOptimizationRequest(
        String replanId,
        Long simulationRunId,
        Long snapshotVersion,
        Long simulationClockMillis,
        Long warehouseId,
        ReoptimizationReason reason,
        Long triggerRobotId,
        List<Long> blockedEdgeIds,
        String description,

        Long pickingDurationMillis,
        Long droppingDurationMillis,

        List<RobotStateInput> robots,
        List<TaskInput> remainingTasks
) {

    public ReoptimizationOptimizationRequest {
        blockedEdgeIds = blockedEdgeIds == null
                ? List.of()
                : List.copyOf(blockedEdgeIds);
        robots = robots == null ? List.of() : List.copyOf(robots);
        remainingTasks = remainingTasks == null
                ? List.of()
                : List.copyOf(remainingTasks);
        if (pickingDurationMillis != null && pickingDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "pickingDurationMillis must be positive"
            );
        }
        if (droppingDurationMillis != null && droppingDurationMillis <= 0) {
            throw new IllegalArgumentException(
                    "droppingDurationMillis must be positive"
            );
        }
    }

    /** Compatibility constructor for tests that exercise the pre-window contract. */
    public ReoptimizationOptimizationRequest(
            String replanId,
            Long simulationRunId,
            Long snapshotVersion,
            Long simulationClockMillis,
            Long warehouseId,
            ReoptimizationReason reason,
            Long triggerRobotId,
            List<Long> blockedEdgeIds,
            String description,
            List<RobotStateInput> robots,
            List<TaskInput> remainingTasks
    ) {
        this(
                replanId,
                simulationRunId,
                snapshotVersion,
                simulationClockMillis,
                warehouseId,
                reason,
                triggerRobotId,
                blockedEdgeIds,
                description,
                null,
                null,
                robots,
                remainingTasks
        );
    }

    public record RobotStateInput(
            Long robotId,
            Long currentNodeId,
            Double batteryLevel,
            String status,
            Long currentTaskId,
            String runtimePhase,
            RemainingStage remainingStage
    ) {
    }

    public enum RemainingStage {
        TO_START,
        PICKING,
        TO_END,
        DROPPING,
        IDLE
    }

    public record TaskInput(
            Long taskId,
            Long currentlyAssignedRobotId,
            Long startNodeId,
            Long endNodeId,
            String taskType,
            String status
    ) {
    }
}
