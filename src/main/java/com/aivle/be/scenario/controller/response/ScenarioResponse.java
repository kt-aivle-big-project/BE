package com.aivle.be.scenario.controller.response;

import com.aivle.be.scenario.entity.Scenario;

public record ScenarioResponse(
        Long id,
        Long warehouseId,
        String scenarioCode,
        String scenarioName,
        Integer robotCount,
        Double simulationSpeed,
        Integer chargingThreshold,
        Boolean autoReplan,
        Boolean obstacleEnabled
) {
    public static ScenarioResponse from(Scenario scenario) {
        return new ScenarioResponse(
                scenario.getId(),
                scenario.getWarehouse().getId(),
                scenario.getScenarioCode(),
                scenario.getScenarioName(),
                scenario.getRobotCount(),
                scenario.getSimulationSpeed(),
                scenario.getChargingThreshold(),
                scenario.getAutoReplan(),
                scenario.getObstacleEnabled()
        );
    }
}
