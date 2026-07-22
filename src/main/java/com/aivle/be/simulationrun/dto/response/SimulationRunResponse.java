package com.aivle.be.simulationrun.dto.response;

import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.simulationrun.entity.SimulationRun;

import java.time.LocalDateTime;

public record SimulationRunResponse(
        Long simulationRunId,
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
        Integer generationIntervalSeconds
) {
    public static SimulationRunResponse from(SimulationRun run) {
        return new SimulationRunResponse(
                run.getId(),
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
                run.getGenerationIntervalSeconds()
        );
    }
}
