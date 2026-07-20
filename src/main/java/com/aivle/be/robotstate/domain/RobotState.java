package com.aivle.be.robotstate.domain;

import java.time.LocalDateTime;

public record RobotState(
        Long robotId,
        Long warehouseId,
        Long currentNodeId,
        Integer batteryLevel,
        RobotStatus status,
        Long currentTaskId,
        LocalDateTime updatedAt
) {
}
