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
    }
}
