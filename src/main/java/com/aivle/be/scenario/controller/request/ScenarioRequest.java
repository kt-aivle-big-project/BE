package com.aivle.be.scenario.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScenarioRequest(
        @NotNull Long warehouseId,
        @NotBlank String scenarioName,

        @Size(max = 50) String scenarioCode,

        @Size(max = 500) String description,

        // 기본값 : 배터리 100%, 충전 기준 20%
        @Min(0) @Max(100) Integer initialBattery,
        @Min(0) @Max(100) Integer chargingThreshold,

        @Min(1) @Max(100) Integer robotCount,
        Double simulationSpeed,
        Boolean autoReplan,
        Boolean obstacleEnabled
) {
}
