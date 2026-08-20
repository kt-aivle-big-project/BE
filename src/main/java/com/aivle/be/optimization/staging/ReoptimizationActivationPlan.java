package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.entity.ReoptimizationStagedPathStep;
import com.aivle.be.optimization.entity.ReoptimizationStagedTaskPlan;

import java.util.List;

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
            OperationWindowView pickingWindow,
            OperationWindowView droppingWindow,
            List<PathStepView> pathToStart,
            List<PathStepView> pathToEnd
    ) {

        public TaskPlanView {
            pathToStart = List.copyOf(pathToStart);
            pathToEnd = List.copyOf(pathToEnd);
        }

        public TaskPlanView(
                Long robotId,
                Long taskId,
                Integer sequence,
                TaskPlan.ExecutionStage executionStage,
                Long estimatedStartTimeMillis,
                Long estimatedCompletionTimeMillis,
                List<PathStepView> pathToStart,
                List<PathStepView> pathToEnd
        ) {
            this(
                    robotId,
                    taskId,
                    sequence,
                    executionStage,
                    estimatedStartTimeMillis,
                    estimatedCompletionTimeMillis,
                    inferredPicking(executionStage, pathToStart, pathToEnd),
                    inferredDropping(pathToEnd),
                    pathToStart,
                    pathToEnd
            );
        }

        private static OperationWindowView inferredPicking(
                TaskPlan.ExecutionStage stage,
                List<PathStepView> pathToStart,
                List<PathStepView> pathToEnd
        ) {
            if (stage == TaskPlan.ExecutionStage.TO_END) {
                return null;
            }
            PathStepView end = pathToStart.get(pathToStart.size() - 1);
            long start = end.departureTimeMillis();
            return new OperationWindowView(end.nodeId(), start, start + 1);
        }

        private static OperationWindowView inferredDropping(
                List<PathStepView> pathToEnd
        ) {
            PathStepView end = pathToEnd.get(pathToEnd.size() - 1);
            long start = end.departureTimeMillis();
            return new OperationWindowView(end.nodeId(), start, start + 1);
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
                    OperationWindowView.of(
                            taskPlan.getPickingNodeId(),
                            taskPlan.getPickingStartTimeMillis(),
                            taskPlan.getPickingEndTimeMillis()
                    ),
                    OperationWindowView.of(
                            taskPlan.getDroppingNodeId(),
                            taskPlan.getDroppingStartTimeMillis(),
                            taskPlan.getDroppingEndTimeMillis()
                    ),
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

    public record OperationWindowView(
            Long nodeId,
            Long startTimeMillis,
            Long endTimeMillis
    ) {
        private static OperationWindowView of(
                Long nodeId,
                Long startTimeMillis,
                Long endTimeMillis
        ) {
            return nodeId == null || startTimeMillis == null
                    || endTimeMillis == null
                    ? null
                    : new OperationWindowView(
                            nodeId,
                            startTimeMillis,
                            endTimeMillis
                    );
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
