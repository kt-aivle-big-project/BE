package com.aivle.be.warehouse.dto;

public record WarehouseImportResponse(
        Long warehouseId,
        String name,

        int nodeCount,
        int routeNodeCount,
        int edgeCount,
        int rackCount,
        int chargingStationCount,
        int robotCount,

        int skippedNodeCount
) {}
