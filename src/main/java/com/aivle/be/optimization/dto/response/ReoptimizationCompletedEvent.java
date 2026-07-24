package com.aivle.be.optimization.dto.response;

import com.aivle.be.optimization.domain.ReoptimizationReason;

import java.time.LocalDateTime;
import java.util.List;

public record ReoptimizationCompletedEvent(
        String eventType,
        Long simulationRunId,
        Long optimizationResultId,
        String requestId,
        String status,
        ReoptimizationReason reason,
        Long triggerRobotId,
        List<Long> changedTaskIds,
        List<Long> affectedRobotIds,
        LocalDateTime occurredAt
) {

    private static final String EVENT_TYPE =
            "REOPTIMIZATION_COMPLETED";

    public static ReoptimizationCompletedEvent of(
            Long simulationRunId,
            Long optimizationResultId,
            ReoptimizationResponse response,
            ReoptimizationReason reason,
            Long triggerRobotId,
            List<Long> changedTaskIds,
            List<Long> affectedRobotIds
    ) {
        return new ReoptimizationCompletedEvent(
                EVENT_TYPE,
                simulationRunId,
                optimizationResultId,
                response.requestId(),
                response.status(),
                reason,
                triggerRobotId,
                changedTaskIds,
                affectedRobotIds,
                LocalDateTime.now()
        );
    }
}