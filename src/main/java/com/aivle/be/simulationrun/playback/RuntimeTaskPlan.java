package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.dto.response.TaskPlan;

import java.util.List;
import java.util.Objects;

/** Immutable task-level plan kept separately from legacy playback fields. */
public record RuntimeTaskPlan(
        Long taskId,
        Integer sequence,
        TaskPlan.ExecutionStage executionStage,
        List<RuntimePathStep> pathToStart,
        List<RuntimePathStep> pathToEnd,
        RuntimeOperationWindow pickingWindow,
        RuntimeOperationWindow droppingWindow,
        Long estimatedStartTimeMillis,
        Long estimatedCompletionTimeMillis
) {

    public RuntimeTaskPlan {
        Objects.requireNonNull(taskId, "taskId is required");
        Objects.requireNonNull(sequence, "sequence is required");
        Objects.requireNonNull(
                executionStage,
                "executionStage is required"
        );
        Objects.requireNonNull(
                estimatedStartTimeMillis,
                "estimatedStartTimeMillis is required"
        );
        Objects.requireNonNull(
                estimatedCompletionTimeMillis,
                "estimatedCompletionTimeMillis is required"
        );
        pathToStart = pathToStart == null
                ? List.of()
                : List.copyOf(pathToStart);
        pathToEnd = pathToEnd == null
                ? List.of()
                : List.copyOf(pathToEnd);
        if (executionStage == TaskPlan.ExecutionStage.FULL
                && pickingWindow == null) {
            throw new IllegalArgumentException(
                    "FULL runtime task requires pickingWindow"
            );
        }
        if (executionStage == TaskPlan.ExecutionStage.TO_END
                && pickingWindow != null) {
            throw new IllegalArgumentException(
                    "TO_END runtime task must not have pickingWindow"
            );
        }
        Objects.requireNonNull(
                droppingWindow,
                "droppingWindow is required"
        );
    }
}
