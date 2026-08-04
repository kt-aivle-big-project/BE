package com.aivle.be.warehouse.dto;

public record WarehouseMapSyncResponse(
        Long warehouseId,
        String aiWarehouseId,
        boolean postgresSynchronized,
        boolean neo4jSynchronized
) {}
