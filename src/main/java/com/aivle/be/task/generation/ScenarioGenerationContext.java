package com.aivle.be.task.generation;

import com.aivle.be.simulationrun.domain.ScenarioType;

public record ScenarioGenerationContext(
        Long simulationRunId,
        Long warehouseId,
        ScenarioType scenarioType,
        Long seed,
        Integer taskCount,
        Double inboundRatio,
        Integer generationIntervalSeconds
) {
}
