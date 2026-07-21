package com.aivle.be.chargingstation.dto.response;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.entity.ChargingStation.ChargingStationStatus;

public record ChargingStationResponse(
        Long id,
        Long warehouseId,
        Long nodeId,
        String name,
        ChargingStationStatus status,
        Double chargingPower
) {

    public static ChargingStationResponse from(ChargingStation station) {
        return new ChargingStationResponse(
                station.getId(),
                station.getWarehouse().getId(),
                station.getNode().getId(),
                station.getName(),
                station.getStatus(),
                station.getChargingPower()
        );
    }
}