package com.aivle.be.optimization.dto.response;

import java.util.List;
import java.util.Objects;

/**
 * AI가 반환하는 작업 단위 실행 계획.
 *
 * pathToStart와 pathToEnd는 각 구간의 시작 노드와 끝 노드를 모두 포함한다.
 * executionStage가 TO_END이면 pathToStart는 빈 배열이어야 한다.
 */
public record TaskPlan(
        Long robotId,
        Long taskId,
        Integer sequence,
        ExecutionStage executionStage,
        List<PathStep> pathToStart,
        List<PathStep> pathToEnd,
        TaskOperationWindow pickingWindow,
        TaskOperationWindow droppingWindow,
        Long estimatedStartTimeMillis,
        Long estimatedCompletionTimeMillis
) {

    public TaskPlan {
        Objects.requireNonNull(robotId, "robotId is required");
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

        if (executionStage == ExecutionStage.FULL
                && pathToStart.isEmpty()) {
            throw new IllegalArgumentException(
                    "FULL task plan requires pathToStart"
            );
        }

        if (executionStage == ExecutionStage.TO_END
                && !pathToStart.isEmpty()) {
            throw new IllegalArgumentException(
                    "TO_END task plan requires an empty pathToStart"
            );
        }

        if (pathToEnd.isEmpty()) {
            throw new IllegalArgumentException(
                    "Task plan requires pathToEnd"
            );
        }

        validateMonotonicPath(pathToStart, "pathToStart");
        validateMonotonicPath(pathToEnd, "pathToEnd");

        if (!pathToStart.isEmpty()) {
            PathStep lastToStart = pathToStart.get(
                    pathToStart.size() - 1
            );
            PathStep firstToEnd = pathToEnd.get(0);

            if (firstToEnd.arrivalTimeMillis()
                    < lastToStart.departureTimeMillis()) {
                throw new IllegalArgumentException(
                        "pathToEnd cannot start before pathToStart departs"
                );
            }
        }

        PathStep firstStep = pathToStart.isEmpty()
                ? pathToEnd.get(0)
                : pathToStart.get(0);
        PathStep lastStep = pathToEnd.get(pathToEnd.size() - 1);

        if (!estimatedStartTimeMillis.equals(
                firstStep.arrivalTimeMillis()
        )) {
            throw new IllegalArgumentException(
                    "estimatedStartTimeMillis must match the first arrival"
            );
        }

        Long expectedCompletion = droppingWindow == null
                ? lastStep.departureTimeMillis()
                : droppingWindow.endTimeMillis();
        if (!estimatedCompletionTimeMillis.equals(expectedCompletion)) {
            throw new IllegalArgumentException(
                    "estimatedCompletionTimeMillis must match dropping end"
            );
        }
    }

    /** Compatibility constructor for pre-operation-window callers. */
    public TaskPlan(
            Long robotId,
            Long taskId,
            Integer sequence,
            ExecutionStage executionStage,
            List<PathStep> pathToStart,
            List<PathStep> pathToEnd,
            Long estimatedStartTimeMillis,
            Long estimatedCompletionTimeMillis
    ) {
        this(
                robotId,
                taskId,
                sequence,
                executionStage,
                pathToStart,
                pathToEnd,
                null,
                null,
                estimatedStartTimeMillis,
                estimatedCompletionTimeMillis
        );
    }

    private static void validateMonotonicPath(
            List<PathStep> path,
            String fieldName
    ) {
        for (int index = 1; index < path.size(); index++) {
            PathStep previous = path.get(index - 1);
            PathStep current = path.get(index);

            if (current.arrivalTimeMillis()
                    < previous.arrivalTimeMillis()) {
                throw new IllegalArgumentException(
                        fieldName + " arrival times must be monotonic"
                );
            }

            if (current.arrivalTimeMillis()
                    < previous.departureTimeMillis()) {
                throw new IllegalArgumentException(
                        fieldName + " steps cannot overlap in time"
                );
            }
        }
    }

    public enum ExecutionStage {
        FULL,
        TO_END
    }
}
