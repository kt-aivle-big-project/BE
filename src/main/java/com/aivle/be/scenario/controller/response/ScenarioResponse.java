package com.aivle.be.scenario.controller.response;

import com.aivle.be.scenario.domain.ScenarioStatus;
import com.aivle.be.scenario.entity.Scenario;

import java.time.LocalDateTime;

public record ScenarioResponse(
        Long id,
        Long warehouseId,
        String warehouseName,
        String scenarioCode,
        String scenarioName,
        String description,
        ScenarioStatus status,
        Integer initialBattery,
        Integer chargingThreshold,
        Integer robotCount,
        Double simulationSpeed,
        Boolean autoReplan,
        Boolean obstacleEnabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ScenarioResponse from(Scenario scenario) {
        return new ScenarioResponse(
                scenario.getId(),
                scenario.getWarehouse() == null ? null : scenario.getWarehouse().getId(),
                scenario.getWarehouse() == null ? null : scenario.getWarehouse().getName(),
                scenario.getScenarioCode(),
                scenario.getScenarioName(),
                scenario.getDescription(),
                scenario.getStatus(),
                scenario.getInitialBattery(),
                scenario.getChargingThreshold(),
                scenario.getRobotCount(),
                scenario.getSimulationSpeed(),
                scenario.getAutoReplan(),
                scenario.getObstacleEnabled(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt()
        );
    }
}
