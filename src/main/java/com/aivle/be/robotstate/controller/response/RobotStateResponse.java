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

        @Schema(description = "현재 MOVE 구간 식별자", example = "R10006-0018", nullable = true)
        String movementStepId,

        @Schema(description = "MOVE 구간의 시뮬레이션 시작 시각(ms)", example = "12992", nullable = true)
        Long movementStartAtMillis,

        @Schema(description = "MOVE 구간의 시뮬레이션 종료 시각(ms)", example = "13762", nullable = true)
        Long movementEndAtMillis,

        @Schema(description = "상태 생성 시점의 시뮬레이션 시각(ms)", example = "13300", nullable = true)
        Long simulationTimeMillis,

        @Schema(description = "현재 MOVE 구간 진행률(0~1)", example = "0.4", nullable = true)
        Double movementProgress,

        Integer batteryLevel,
        RobotStatus status,
        Long currentTaskId,
        @Schema(description = "현재 작업 유형", example = "INBOUND", nullable = true)
        String taskType,
        @Schema(description = "화면 아이콘에 사용할 실제 활동", example = "PUTAWAY", nullable = true)
        RobotStatus activity,
        @Schema(description = "현재 SERVICE 단계", example = "PICKUP", nullable = true)
        String serviceKind,
        @Schema(description = "현재 SERVICE 진행률(0~1)", example = "0.5", nullable = true)
        Double serviceProgress,
        @Schema(description = "BOX 적재 여부", example = "true")
        Boolean carryingLoad,
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
                state.movementStepId(),
                state.movementStartAtMillis(),
                state.movementEndAtMillis(),
                state.simulationTimeMillis(),
                state.movementProgress(),
                state.batteryLevel(),
                state.status(),
                state.currentTaskId(),
                state.taskType(),
                state.activity(),
                state.serviceKind(),
                state.serviceProgress(),
                state.carryingLoad(),
                state.updatedAt()
        );
    }
}
