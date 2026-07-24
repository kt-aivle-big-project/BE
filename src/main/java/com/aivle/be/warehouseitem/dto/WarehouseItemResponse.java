package com.aivle.be.warehouseitem.dto;

import com.aivle.be.warehouseitem.entity.WarehouseItem;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WarehouseItemResponse(
        Long id,
        Long warehouseId,
        Long storageLocationId,
        Long nodeId,
        Long itemId,
        LocalDate expiryDate,
        LocalDateTime receivedAt,
        Integer quantity,
        Integer inboundQuantity,
        Integer outboundQuantity
) {
    public static WarehouseItemResponse from(WarehouseItem item) {
        return new WarehouseItemResponse(
                item.getId(),
                item.getWarehouse().getId(),
                item.getStorageLocation().getId(),
                item.getNode().getId(),
                item.getItemId(),
                item.getExpiryDate(),
                item.getReceivedAt(),
                item.getQuantity(),
                item.getInboundQuantity(),
                item.getOutboundQuantity()
        );
    }
}
