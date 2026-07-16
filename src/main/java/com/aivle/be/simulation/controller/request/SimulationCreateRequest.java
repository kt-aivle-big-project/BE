package com.aivle.be.simulation.controller.request;

public record SimulationCreateRequest(
        Long warehouseId,
        Long missionId,
        Long robotId,
        Long startNode,
        Long endNode,
        String taskCode
) {
}