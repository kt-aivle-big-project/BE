package com.aivle.be.graph.event;

/** Published after a PostgreSQL warehouse-map mutation commits. */
public record WarehouseGraphChangedEvent(Long warehouseId) {
}
