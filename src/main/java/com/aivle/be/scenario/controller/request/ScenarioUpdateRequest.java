package com.aivle.be.scenario.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// SimulationSetting "설정 저장" 전용 (부분 수정)
public record ScenarioUpdateRequest(
        String scenarioName,
        @Min(1) @Max(100) Integer robotCount,
        Double simulationSpeed,
        @Min(0) @Max(100) Integer initialBattery,
        @Min(0) @Max(100) Integer chargingThreshold,
        Boolean autoReplan,
        Boolean obstacleEnabled
) {
}