package com.aivle.be.robotstate.domain;

import java.time.LocalDateTime;

public record RobotState(
        Long robotId,
        Long warehouseId,
        Long currentNodeId,
        String currentNodeCode,
        Long nextNodeId,
        String nextNodeCode,
        Double arrivalInSeconds,
        String movementStepId,
        Long movementStartAtMillis,
        Long movementEndAtMillis,
        Long simulationTimeMillis,
        Double movementProgress,
        Integer batteryLevel,
        RobotStatus status,
        Long currentTaskId,
        String taskType,
        RobotStatus activity,
        String serviceKind,
        Double serviceProgress,
        Boolean carryingLoad,
        String waitingReason,
        String waitingNodeCode,
        Long blockingRobotId,
        Long waitStartedAtMillis,
        Long estimatedResumeAtMillis,
        LocalDateTime updatedAt
) {
    /**
     * 이동 정보 없이 생성 (정지 상태).
     */
    public static RobotState stationary(
            Long robotId,
            Long warehouseId,
            Long currentNodeId,
            String currentNodeCode,
            Integer batteryLevel,
            RobotStatus status,
            Long currentTaskId,
            LocalDateTime updatedAt
    ) {
        return new RobotState(
                robotId, warehouseId,
                currentNodeId, currentNodeCode,
                null, null, null,
                null, null, null, null, null,
                batteryLevel, status, currentTaskId,
                null, status, null, null, false,
                null, null, null, null, null,
                updatedAt
        );
    }
}
