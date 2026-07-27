package com.aivle.be.robotstate.controller.request;

import com.aivle.be.robotstate.domain.RobotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record RobotStateUpdateRequest(
        @Schema(description = "로봇이 현재 위치한 노드 ID", example = "125")
        @NotNull(message = "현재 노드 ID는 필수입니다.")
        Long currentNodeId,

        @Schema(description = "배터리 잔량(0~100)", example = "67")
        @NotNull(message = "배터리 잔량은 필수입니다.")
        @Min(value = 0, message = "배터리 잔량은 0 이상이어야 합니다.")
        @Max(value = 100, message = "배터리 잔량은 100 이하여야 합니다.")
        Integer batteryLevel,

        @Schema(description = "로봇 운행 상태", example = "MOVING")
        @NotNull(message = "로봇 상태는 필수입니다.")
        RobotStatus status,

        @Schema(description = "현재 수행 중인 작업 ID", example = "301", nullable = true)
        Long currentTaskId,

        @Schema(description = "시뮬레이터가 상태를 생성한 시각")
        @NotNull(message = "상태 생성 시각은 필수입니다.")
        LocalDateTime eventTime
) {
}
