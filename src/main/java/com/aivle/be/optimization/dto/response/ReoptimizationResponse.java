package com.aivle.be.optimization.dto.response;

import java.util.List;

public record ReoptimizationResponse(
        String requestId,
        String replanId,
        Long simulationRunId,
        Long snapshotVersion,
        Status status,
        List<TaskPlan> taskPlans,
        List<Long> unassignedTaskIds,
        String message
) {

    public ReoptimizationResponse {
        taskPlans = taskPlans == null
                ? List.of()
                : List.copyOf(taskPlans);
        unassignedTaskIds = unassignedTaskIds == null
                ? List.of()
                : List.copyOf(unassignedTaskIds);
    }

    public enum Status {
        SUCCEEDED,
        INFEASIBLE,
        FAILED
    }
}
