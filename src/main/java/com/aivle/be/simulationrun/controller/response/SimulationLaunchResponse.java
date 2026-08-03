package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.optimization.dto.response.LaroPlanResponse;

public record SimulationLaunchResponse(
        String aiWarehouseId,
        SimulationRunResponse simulationRun,
        LaroPlanResponse plan
) {
}
