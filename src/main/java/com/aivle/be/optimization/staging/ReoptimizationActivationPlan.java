package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.entity.ReoptimizationStagedPathStep;
import com.aivle.be.optimization.entity.ReoptimizationStagedTaskPlan;

import java.util.List;

/**
 * DB 적용이 끝난 뒤 Phase 2-4 Runtime 설치에 전달할 불변 계획.
 */
public record ReoptimizationActivationPlan(
        Long stageId,
        Long simulationRunId,
        String replanId,
        Long snapshotVersion,
        Long simulationClockMillis,
        ReoptimizationPlanStage.Status status,
        List<TaskPlanView> taskPlans
) {

    public ReoptimizationActivationPlan {
        taskPlans = List.copyOf(taskPlans);
    }

    public static ReoptimizationActivationPlan from(
            ReoptimizationPlanStage stage
    ) {
        return new ReoptimizationActivationPlan(
                stage.getId(),
                stage.getSimulationRun().getId(),
                stage.getReplanId(),
                stage.getSnapshotVersion(),
                stage.getSimulationClockMillis(),
                stage.getStatus(),
                stage.getTaskPlans().stream()
                        .map(TaskPlanView::from)
                        .toList()
        );
    }

    public record TaskPlanView(
            Long robotId,
            Long taskId,
            Integer sequence,
            TaskPlan.ExecutionStage executionStage,
            Long estimatedStartTimeMillis,
            Long estimatedCompletionTimeMillis,
            List<PathStepView> pathToStart,
            List<PathStepView> pathToEnd
    ) {

        public TaskPlanView {
            pathToStart = List.copyOf(pathToStart);
            pathToEnd = List.copyOf(pathToEnd);
        }

        private static TaskPlanView from(
                ReoptimizationStagedTaskPlan taskPlan
        ) {
            return new TaskPlanView(
                    taskPlan.getRobotId(),
                    taskPlan.getTaskId(),
                    taskPlan.getSequence(),
                    taskPlan.getExecutionStage(),
                    taskPlan.getEstimatedStartTimeMillis(),
                    taskPlan.getEstimatedCompletionTimeMillis(),
                    steps(
                            taskPlan,
                            ReoptimizationPlanStageCommand
                                    .SegmentType.TO_START
                    ),
                    steps(
                            taskPlan,
                            ReoptimizationPlanStageCommand
                                    .SegmentType.TO_END
                    )
            );
        }

        private static List<PathStepView> steps(
                ReoptimizationStagedTaskPlan taskPlan,
                ReoptimizationPlanStageCommand.SegmentType segmentType
        ) {
            return taskPlan.getPathSteps().stream()
                    .filter(step -> step.getSegmentType() == segmentType)
                    .map(PathStepView::from)
                    .toList();
        }
    }

    public record PathStepView(
            Integer stepSequence,
            Long nodeId,
            Long arrivalTimeMillis,
            Long departureTimeMillis
    ) {

        private static PathStepView from(
                ReoptimizationStagedPathStep pathStep
        ) {
            return new PathStepView(
                    pathStep.getStepSequence(),
                    pathStep.getNodeId(),
                    pathStep.getArrivalTimeMillis(),
                    pathStep.getDepartureTimeMillis()
            );
        }
    }
}
