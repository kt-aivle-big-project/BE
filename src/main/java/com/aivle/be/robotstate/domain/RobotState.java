package com.aivle.be.robotstate.domain;

import java.time.LocalDateTime;

/**
 * Redis에 저장되는 로봇 실시간 상태.
 *
 * currentNodeCode 는 프론트 창고 그래프의 노드 식별자("R0_0")로, 화면에서 로봇 위치를 찾을 때 사용한다.
 *
 * nextNodeCode / arrivalInSeconds 는 화면에서 로봇을 부드럽게 이동시키기 위한 정보다.
 * 프론트는 현재 노드에서 다음 노드까지를 arrivalInSeconds 동안 보간해서 그린다.
 * 이동 중이 아니면 두 값 모두 null 이다.
 */
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
