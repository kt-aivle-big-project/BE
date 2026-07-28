package com.aivle.be.chargingstation.dto.request;

import com.aivle.be.chargingstation.entity.ChargingStation.ChargingStationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChargingStationRequest(

        @NotNull
        Long warehouseId,

        @NotNull
        Long nodeId,

        @NotBlank
        String name,

        @NotNull
        ChargingStationStatus status,

        @Schema(
                description = "시뮬레이션 시간 1분당 배터리 충전량(%)",
                example = "50.0"
        )
        @NotNull
        @Positive
        Double chargingPower
) {
}
