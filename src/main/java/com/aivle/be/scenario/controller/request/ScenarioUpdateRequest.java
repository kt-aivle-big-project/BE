package com.aivle.be.scenario.controller.request;

import com.aivle.be.scenario.domain.ScenarioStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ScenarioUpdateRequest(
        String scenarioName,
        @Size(max = 500) String description,
        @Min(0) @Max(100) Integer initialBattery,
        @Min(0) @Max(100) Integer chargingThreshold,
        @Min(1) @Max(100) Integer robotCount,
        Double simulationSpeed,
        Boolean autoReplan,
        Boolean obstacleEnabled,
        ScenarioStatus status
) {
}
