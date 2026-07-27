package com.aivle.be.simulationrun.controller.request;

import com.aivle.be.simulationrun.domain.ArrivalPattern;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 시뮬레이션 입고 설정.
 */
public record InboundConfigRequest(

        @NotNull
        @Min(0)
        @Schema(description = "입고 예정 건수", example = "10")
        Integer inboundCount,

        @NotNull
        @Min(0)
        @Schema(description = "총 입고 예정량(BOX)", example = "200")
        Integer totalQuantity,

        @NotNull
        @Schema(description = "입고 발생 패턴", example = "RANDOM")
        ArrivalPattern arrivalPattern,

        @Valid
        @NotNull
        @Schema(description = "품목 구성 (비율 합계 100)")
        List<ProductRatio> products
) {

    /**
     * 품목 구성 비율의 합계를 반환한다.
     */
    public int ratioTotal() {
        if (products == null) {
            return 0;
        }
        return products.stream()
                .mapToInt(product -> product.ratio() == null ? 0 : product.ratio())
                .sum();
    }

    public record ProductRatio(

            @NotBlank
            @Schema(description = "품목 코드", example = "A")
            String productCode,

            @NotNull
            @Min(0)
            @Max(100)
            @Schema(description = "구성 비율(%)", example = "50")
            Integer ratio
    ) {
    }
}
