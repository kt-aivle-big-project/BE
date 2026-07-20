package com.aivle.be.simulation.controller.request;

import java.util.List;

public record SimulationPathUpdateRequest(
        List<Long> pathNodes
) {
}