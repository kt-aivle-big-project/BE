package com.aivle.be.warehouseitem.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record WarehouseItemRequest(
        @NotNull
        @Schema(description = "품목이 속한 창고 ID", example = "1")
        Long warehouseId,

        @NotNull
        @Schema(description = "품목이 보관된 보관위치 ID (노드는 이 보관위치에서 자동으로 결정됨)", example = "10")
        Long storageLocationId,

        @Min(1) @Max(3)
        @Schema(description = "선반 층. 생략하면 해당 선반의 가장 낮은 빈 층을 자동 선택", example = "2")
        Integer rackLevel,

        @NotNull
        @Schema(description = "품목 식별자", example = "1001")
        Long itemId,

        @NotNull
        @PositiveOrZero
        @Schema(description = "현재 재고 수량", example = "50")
        Integer quantity
) {
}
