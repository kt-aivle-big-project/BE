package com.aivle.be.simulationrun.controller.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SimulationLaunchRequest(
        @NotNull @Valid SimulationRunCreateRequest simulation,
        String optimizationBackend,
        String userCommand
) {
}
