package com.aivle.be.simulation.controller.response;

import java.util.List;

public record PathOverlapResponse(
        Long nodeId,
        boolean overlapping,
        List<Long> affectedSimulationIds
) {
}