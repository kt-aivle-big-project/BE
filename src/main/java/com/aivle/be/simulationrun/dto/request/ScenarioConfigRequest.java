package com.aivle.be.simulationrun.dto.request;

import com.aivle.be.simulationrun.domain.ScenarioType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public record ScenarioConfigRequest(
        ScenarioType type,
        Long seed,
        @Min(1) Integer taskCount,
        @DecimalMin("0.0") @DecimalMax("1.0") Double inboundRatio,
        @Min(0) Integer generationIntervalSeconds
) {
}
