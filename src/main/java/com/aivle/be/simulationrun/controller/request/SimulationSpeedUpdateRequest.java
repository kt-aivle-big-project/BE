package com.aivle.be.simulationrun.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SimulationSpeedUpdateRequest(

        @NotNull
        @Positive
        @Schema(description = "실행 배속 (0.5 / 1 / 2 / 3)", example = "2")
        Double simulationSpeed
) {
}
