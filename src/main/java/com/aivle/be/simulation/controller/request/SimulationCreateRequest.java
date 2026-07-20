package com.aivle.be.simulation.controller.request;

import java.util.List;

public record SimulationCreateRequest(
        Long warehouseId,
        Long missionId,
        Long robotId,
        Long startNode,
        Long endNode,
        String taskCode,
        List<Long> pathNodes
) {
}