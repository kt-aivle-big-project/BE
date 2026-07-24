package com.aivle.be.warehouseitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public record WarehouseItemRequest(
        @NotNull
        @Schema(description = "품목이 속한 창고 ID", example = "1")
        Long warehouseId,

        @NotNull
        @Schema(description = "품목이 보관된 보관위치 ID (노드는 이 보관위치에서 자동으로 결정됨)", example = "10")
        Long storageLocationId,

        @NotNull
        @Schema(description = "품목 식별자", example = "1001")
        Long itemId,

        @Schema(description = "유통기한 (없으면 null)", example = "2026-12-31", nullable = true)
        LocalDate expiryDate,

        @NotNull
        @PositiveOrZero
        @Schema(description = "현재 재고 수량", example = "50")
        Integer quantity
) {
}
