package com.aivle.be.robotstate.dto.response;

import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;

import java.time.LocalDateTime;

public record RobotStateResponse(
        Long robotId,
        Long warehouseId,
        Long currentNodeId,
        Integer batteryLevel,
        RobotStatus status,
        Long currentTaskId,
        LocalDateTime updatedAt
) {
    public static RobotStateResponse from(RobotState state) {
        return new RobotStateResponse(
                state.robotId(),
                state.warehouseId(),
                state.currentNodeId(),
                state.batteryLevel(),
                state.status(),
                state.currentTaskId(),
                state.updatedAt()
        );
    }
}
