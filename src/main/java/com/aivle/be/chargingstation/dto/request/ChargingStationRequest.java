package com.aivle.be.chargingstation.dto.request;

import com.aivle.be.chargingstation.entity.ChargingStation.ChargingStationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChargingStationRequest(

        @NotNull
        Long warehouseId,

        @NotNull
        Long nodeId,

        @NotBlank
        String name,

        @NotNull
        ChargingStationStatus status,

        @NotNull
        Double chargingPower
) {
}