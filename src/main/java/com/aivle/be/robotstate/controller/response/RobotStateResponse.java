package com.aivle.be.robotstate.controller.response;

import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

public record RobotStateResponse(
        Long robotId,
        Long warehouseId,
        Long currentNodeId,

        @Schema(description = "현재 위치 노드 코드", example = "R6_1")
        String currentNodeCode,

        @Schema(description = "이동 중인 다음 노드 ID (정지 중이면 null)", nullable = true)
        Long nextNodeId,

        @Schema(description = "이동 중인 다음 노드 코드 (정지 중이면 null)", example = "R6_2", nullable = true)
        String nextNodeCode,

        @Schema(
                description = "다음 노드까지 남은 시간(초). 화면 보간에 사용",
                example = "2.0",
                nullable = true
        )
        Double arrivalInSeconds,

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
                state.currentNodeCode(),
                state.nextNodeId(),
                state.nextNodeCode(),
                state.arrivalInSeconds(),
                state.batteryLevel(),
                state.status(),
                state.currentTaskId(),
                state.updatedAt()
        );
    }
}
