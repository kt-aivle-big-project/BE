package com.aivle.be.optimization.dto.response;

import java.util.List;

// TODO: FastAPI·cuOpt 최종 응답 스펙 확정 후 필드명과 자료형 조정
public record OptimizationResponse(
        String requestId,
        String status,
        List<RobotRoute> routes
) {

    public record RobotRoute(
            Long robotId,
            List<Long> nodePath,
            Double totalDistance,
            Double estimatedTime
    ) {
    }
}