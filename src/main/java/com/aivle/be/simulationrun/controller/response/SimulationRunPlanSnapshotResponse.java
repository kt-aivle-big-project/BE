package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;

import java.time.LocalDateTime;

public record SimulationRunPlanSnapshotResponse(
        Long id,
        Long simulationRunId,
        long cycleMinute,
        LaroPlanRequest planRequest,
        LaroPlanResponse planResponse,
        LocalDateTime createdAt
) {
}
