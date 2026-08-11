package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.simulationrun.entity.SimulationRun;

import java.time.LocalDateTime;

public record SimulationRunResponse(
        Long simulationRunId,
        long executionVersion,
        Long warehouseId,
        SimulationRunStatus status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime pausedAt,
        LocalDateTime endedAt,
        ScenarioType scenarioType,
        Long randomSeed,
        Integer plannedTaskCount,
        Double inboundRatio,
        Integer generationIntervalSeconds,

        // 시나리오 프리셋 및 실행 설정
        Long scenarioId,
        String scenarioCode,
        String scenarioName,
        Double simulationSpeed,
        Integer robotCount,
        Integer chargingThreshold,
        Boolean autoReplan,
        Boolean obstacleEnabled
) {
    public static SimulationRunResponse from(SimulationRun run) {
        Scenario scenario = run.getScenario();

        return new SimulationRunResponse(
                run.getId(),
                run.getExecutionVersion(),
                run.getWarehouse().getId(),
                run.getStatus(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getPausedAt(),
                run.getEndedAt(),
                run.getScenarioType() == null ? ScenarioType.MANUAL : run.getScenarioType(),
                run.getRandomSeed(),
                run.getPlannedTaskCount(),
                run.getInboundRatio(),
                run.getGenerationIntervalSeconds(),
                scenario == null ? null : scenario.getId(),
                scenario == null ? null : scenario.getScenarioCode(),
                scenario == null ? null : scenario.getScenarioName(),
                run.getSimulationSpeed(),
                run.getRobotCount(),
                run.getChargingThreshold(),
                run.getAutoReplan(),
                run.getObstacleEnabled()
        );
    }
}
