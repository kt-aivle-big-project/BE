package com.aivle.be.optimization.dto.response;

import java.util.List;

public record ReoptimizationResponse(
        String requestId,
        String status,
        List<TaskAssignment> assignments,
        List<RobotRoute> routes
) {

    public record TaskAssignment(
            Long taskId,
            Long robotId
    ) {
    }

    public record RobotRoute(
            Long robotId,
            List<Long> nodePath,
            Double totalDistance,
            Double estimatedTime
    ) {
    }
}