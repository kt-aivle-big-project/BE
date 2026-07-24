package com.aivle.be.optimization.dto.response;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.entity.OptimizationResult;
import com.aivle.be.optimization.entity.RobotRouteResult;
import com.aivle.be.optimization.entity.TaskAssignmentResult;

import java.time.LocalDateTime;
import java.util.List;

public record ReoptimizationHistoryResponse(
        Long optimizationResultId,
        String requestId,
        Long warehouseId,
        Long simulationRunId,
        String status,
        ReoptimizationReason reason,
        Long triggerRobotId,
        String description,
        LocalDateTime createdAt,
        List<RouteHistory> routes,
        List<TaskAssignmentHistory> taskAssignments
) {

    public static ReoptimizationHistoryResponse from(
            OptimizationResult result
    ) {
        List<RouteHistory> routes = result.getRoutes().stream()
                .map(RouteHistory::from)
                .toList();

        List<TaskAssignmentHistory> taskAssignments =
                result.getTaskAssignments().stream()
                        .map(TaskAssignmentHistory::from)
                        .toList();

        return new ReoptimizationHistoryResponse(
                result.getId(),
                result.getRequestId(),
                result.getWarehouseId(),
                result.getSimulationRunId(),
                result.getStatus(),
                result.getReoptimizationReason(),
                result.getTriggerRobotId(),
                result.getDescription(),
                result.getCreatedAt(),
                routes,
                taskAssignments
        );
    }

    public record RouteHistory(
            Long robotId,
            String nodePath,
            Double totalDistance,
            Double estimatedTime
    ) {

        public static RouteHistory from(
                RobotRouteResult route
        ) {
            return new RouteHistory(
                    route.getRobotId(),
                    route.getNodePath(),
                    route.getTotalDistance(),
                    route.getEstimatedTime()
            );
        }
    }

    public record TaskAssignmentHistory(
            Long taskId,
            Long previousRobotId,
            Long assignedRobotId
    ) {

        public static TaskAssignmentHistory from(
                TaskAssignmentResult assignment
        ) {
            return new TaskAssignmentHistory(
                    assignment.getTaskId(),
                    assignment.getPreviousRobotId(),
                    assignment.getAssignedRobotId()
            );
        }
    }
}