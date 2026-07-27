package com.aivle.be.simulationrun.controller.response;

import java.util.List;

public record SimulationRunParticipantsResponse(
        Long simulationRunId,
        List<Long> robotIds
) {
}
