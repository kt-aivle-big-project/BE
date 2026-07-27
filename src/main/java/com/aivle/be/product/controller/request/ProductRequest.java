package com.aivle.be.product.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ProductRequest(

        @NotBlank(message = "품목 코드는 필수입니다.")
        @Schema(description = "품목 코드", example = "A")
        String productCode,

        @NotBlank(message = "품목명은 필수입니다.")
        @Schema(description = "품목명", example = "식품")
        String productName
) {
}
