package com.aivle.be.scenario.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ScenarioRequest(
        @NotNull Long warehouseId,
        @NotBlank String scenarioCode,
        @NotBlank String scenarioName,
        @NotNull @Min(1) @Max(100) Integer robotCount,
        @NotNull Double simulationSpeed,
        @NotNull @Min(0) @Max(100) Integer chargingThreshold,
        @NotNull Boolean autoReplan,
        @NotNull Boolean obstacleEnabled
) {
}
