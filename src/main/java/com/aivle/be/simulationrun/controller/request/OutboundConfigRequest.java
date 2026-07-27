package com.aivle.be.simulationrun.controller.request;

import com.aivle.be.simulationrun.domain.ArrivalPattern;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 시뮬레이션 출고 설정.
 */
public record OutboundConfigRequest(

        @NotNull
        @Min(0)
        @Schema(description = "출고 주문 건수", example = "30")
        Integer orderCount,

        @NotNull
        @Min(0)
        @Schema(description = "총 출고 예정량(BOX)", example = "120")
        Integer totalQuantity,

        @NotNull
        @Schema(description = "주문 발생 패턴", example = "RANDOM")
        ArrivalPattern arrivalPattern,

        @NotNull
        @Min(0)
        @Schema(description = "출고 처리기한(분)", example = "10")
        Integer processingDeadlineMinutes,

        @NotNull
        @Schema(description = "부분 출고 허용 여부", example = "true")
        Boolean allowPartialShipment
) {
}
