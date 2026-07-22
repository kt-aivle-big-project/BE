package com.aivle.be.optimization.dto.request;

import java.util.List;

// TODO: FastAPI·cuOpt 최종 요청 스펙 확정 후 필드명과 자료형 조정
public record OptimizationRequest(
        Long warehouseId,
        List<RobotInput> robots,
        List<NodeInput> nodes,
        List<EdgeInput> edges
) {

    public record RobotInput(
            Long robotId,
            Long currentNodeId,
            Long targetNodeId,
            Double batteryLevel
    ) {
    }

    public record NodeInput(
            Long nodeId,
            Double x,
            Double y
    ) {
    }

    public record EdgeInput(
            Long edgeId,
            Long fromNodeId,
            Long toNodeId,
            Double distance,
            String directionType
    ) {
    }
}