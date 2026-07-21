package com.aivle.be.warehouse.dto;

import com.aivle.be.chargingstation.dto.response.ChargingStationResponse;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeResponse;
import com.aivle.be.warehousenode.dto.WarehouseNodeResponse;
import com.aivle.be.warehousezone.dto.WarehouseZoneResponse;

import java.util.List;

public record WarehouseLayoutResponse(
        WarehouseResponse warehouse,
        List<WarehouseZoneResponse> zones,
        List<WarehouseNodeResponse> nodes,
        List<WarehouseEdgeResponse> edges,
        List<ChargingStationResponse> chargingStations
) {
}