package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 검증이 끝난 AI 계획을 DB I/O와 분리해 운반하는 불변 staging 명령.
 */
public record ReoptimizationPlanStageCommand(
        Long simulationRunId,
        String replanId,
        Long snapshotVersion,
        String requestId,
        Long simulationClockMillis,
        ReoptimizationReason reason,
        Long triggerRobotId,
        String description,
        String responseMessage,
        List<Long> blockedEdgeIds,
        List<TaskPlanCommand> taskPlans
) {

    public ReoptimizationPlanStageCommand {
        Objects.requireNonNull(simulationRunId, "simulationRunId is required");
        Objects.requireNonNull(replanId, "replanId is required");
        Objects.requireNonNull(snapshotVersion, "snapshotVersion is required");
        Objects.requireNonNull(
                simulationClockMillis,
                "simulationClockMillis is required"
        );
        blockedEdgeIds = blockedEdgeIds == null
                ? List.of()
                : List.copyOf(blockedEdgeIds);
        taskPlans = taskPlans == null
                ? List.of()
                : List.copyOf(taskPlans);
    }

    public static ReoptimizationPlanStageCommand from(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response
    ) {
        if (response.status()
                != ReoptimizationResponse.Status.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "Only a validated SUCCEEDED plan can be staged"
            );
        }

        return new ReoptimizationPlanStageCommand(
                request.simulationRunId(),
                request.replanId(),
                request.snapshotVersion(),
                response.requestId(),
                request.simulationClockMillis(),
                request.reason(),
                request.triggerRobotId(),
                request.description(),
                response.message(),
                request.blockedEdgeIds(),
                response.taskPlans().stream()
                        .map(TaskPlanCommand::from)
                        .toList()
        );
    }

    public record TaskPlanCommand(
            Long robotId,
            Long taskId,
            Integer sequence,
            TaskPlan.ExecutionStage executionStage,
            Long estimatedStartTimeMillis,
            Long estimatedCompletionTimeMillis,
            List<PathStepCommand> pathSteps
    ) {

        public TaskPlanCommand {
            pathSteps = pathSteps == null
                    ? List.of()
                    : List.copyOf(pathSteps);
        }

        private static TaskPlanCommand from(TaskPlan plan) {
            List<PathStepCommand> steps = new ArrayList<>();
            appendSteps(
                    steps,
                    SegmentType.TO_START,
                    plan.pathToStart()
            );
            appendSteps(
                    steps,
                    SegmentType.TO_END,
                    plan.pathToEnd()
            );

            return new TaskPlanCommand(
                    plan.robotId(),
                    plan.taskId(),
                    plan.sequence(),
                    plan.executionStage(),
                    plan.estimatedStartTimeMillis(),
                    plan.estimatedCompletionTimeMillis(),
                    steps
            );
        }

        private static void appendSteps(
                List<PathStepCommand> target,
                SegmentType segmentType,
                List<PathStep> source
        ) {
            for (int sequence = 0; sequence < source.size(); sequence++) {
                PathStep step = source.get(sequence);
                target.add(new PathStepCommand(
                        segmentType,
                        sequence,
                        step.nodeId(),
                        step.arrivalTimeMillis(),
                        step.departureTimeMillis()
                ));
            }
        }
    }

    public record PathStepCommand(
            SegmentType segmentType,
            Integer stepSequence,
            Long nodeId,
            Long arrivalTimeMillis,
            Long departureTimeMillis
    ) {
    }

    public enum SegmentType {
        TO_START,
        TO_END
    }
}
