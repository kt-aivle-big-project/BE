package com.aivle.be.product.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProductRequest(

        @NotBlank(message = "품목 코드는 필수입니다.")
        @Schema(description = "품목 코드", example = "ITEM-001")
        String productCode,

        @NotBlank(message = "품목명은 필수입니다.")
        @Schema(description = "품목명", example = "생수 500mL")
        String productName,

        @Size(max = 50)
        @Schema(description = "품목 카테고리", example = "음료")
        String category,

        @Pattern(regexp = "EA")
        @Schema(description = "재고 수량 단위. BOX 내부 낱개 수를 관리하므로 EA 고정", example = "EA")
        String unit,

        @NotNull
        @Positive
        @Schema(description = "보관·운반 BOX 하나에 들어가는 상품 낱개 수", example = "20")
        Integer unitsPerBox,

        @Size(max = 32)
        @Schema(description = "바코드", example = "8800000000001")
        String barcode,

        @Pattern(regexp = "AMBIENT|CHILLED|FROZEN")
        @Schema(description = "보관 온도 구역", example = "AMBIENT")
        String temperatureZone,

        @Schema(description = "파손 주의 여부", example = "false")
        Boolean fragile
) {
}
