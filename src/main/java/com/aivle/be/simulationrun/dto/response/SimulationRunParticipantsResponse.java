package com.aivle.be.simulationrun.dto.response;

import java.util.List;

public record SimulationRunParticipantsResponse(
        Long simulationRunId,
        List<Long> robotIds
) {
}
