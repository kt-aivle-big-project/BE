package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.entity.ReoptimizationStagedPathStep;
import com.aivle.be.optimization.entity.ReoptimizationStagedRobotSnapshot;
import com.aivle.be.optimization.entity.ReoptimizationStagedTaskPlan;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 2-3B가 lazy JPA entity에 의존하지 않고 읽을 수 있는 staging 조회 값.
 */
public record ReoptimizationPlanStageView(
        Long id,
        Long simulationRunId,
        String replanId,
        Long snapshotVersion,
        String requestId,
        ReoptimizationPlanStage.Status status,
        Long simulationClockMillis,
        ReoptimizationReason reason,
        Long triggerRobotId,
        String description,
        String responseMessage,
        LocalDateTime createdAt,
        List<Long> blockedEdgeIds,
        List<RobotSnapshotView> robots,
        List<TaskPlanView> taskPlans
) {

    public ReoptimizationPlanStageView {
        blockedEdgeIds = List.copyOf(blockedEdgeIds);
        robots = List.copyOf(robots);
        taskPlans = List.copyOf(taskPlans);
    }

    public static ReoptimizationPlanStageView from(
            ReoptimizationPlanStage stage
    ) {
        return new ReoptimizationPlanStageView(
                stage.getId(),
                stage.getSimulationRun().getId(),
                stage.getReplanId(),
                stage.getSnapshotVersion(),
                stage.getRequestId(),
                stage.getStatus(),
                stage.getSimulationClockMillis(),
                stage.getReason(),
                stage.getTriggerRobotId(),
                stage.getDescription(),
                stage.getResponseMessage(),
                stage.getCreatedAt(),
                stage.getBlockedEdgeIds(),
                stage.getRobotSnapshots().stream()
                        .map(RobotSnapshotView::from)
                        .toList(),
                stage.getTaskPlans().stream()
                        .map(TaskPlanView::from)
                        .toList()
        );
    }

    public record TaskPlanView(
            Long id,
            Long robotId,
            Long taskId,
            Integer sequence,
            TaskPlan.ExecutionStage executionStage,
            Long snapshotAssignedRobotId,
            String snapshotTaskStatus,
            Long startNodeId,
            Long endNodeId,
            Long estimatedStartTimeMillis,
            Long estimatedCompletionTimeMillis,
            List<PathStepView> pathSteps
    ) {

        public TaskPlanView {
            pathSteps = List.copyOf(pathSteps);
        }

        private static TaskPlanView from(
                ReoptimizationStagedTaskPlan taskPlan
        ) {
            return new TaskPlanView(
                    taskPlan.getId(),
                    taskPlan.getRobotId(),
                    taskPlan.getTaskId(),
                    taskPlan.getSequence(),
                    taskPlan.getExecutionStage(),
                    taskPlan.getSnapshotAssignedRobotId(),
                    taskPlan.getSnapshotTaskStatus(),
                    taskPlan.getStartNodeId(),
                    taskPlan.getEndNodeId(),
                    taskPlan.getEstimatedStartTimeMillis(),
                    taskPlan.getEstimatedCompletionTimeMillis(),
                    taskPlan.getPathSteps().stream()
                            .map(PathStepView::from)
                            .toList()
            );
        }
    }

    public record RobotSnapshotView(
            Long robotId,
            Long currentNodeId,
            Double batteryLevel,
            String status,
            Long currentTaskId,
            String runtimePhase,
            com.aivle.be.optimization.dto.request
                    .ReoptimizationOptimizationRequest.RemainingStage
                    remainingStage
    ) {

        private static RobotSnapshotView from(
                ReoptimizationStagedRobotSnapshot snapshot
        ) {
            return new RobotSnapshotView(
                    snapshot.getRobotId(),
                    snapshot.getCurrentNodeId(),
                    snapshot.getBatteryLevel(),
                    snapshot.getStatus(),
                    snapshot.getCurrentTaskId(),
                    snapshot.getRuntimePhase(),
                    snapshot.getRemainingStage()
            );
        }
    }

    public record PathStepView(
            Long id,
            ReoptimizationPlanStageCommand.SegmentType segmentType,
            Integer stepSequence,
            Long nodeId,
            Long arrivalTimeMillis,
            Long departureTimeMillis
    ) {

        private static PathStepView from(
                ReoptimizationStagedPathStep pathStep
        ) {
            return new PathStepView(
                    pathStep.getId(),
                    pathStep.getSegmentType(),
                    pathStep.getStepSequence(),
                    pathStep.getNodeId(),
                    pathStep.getArrivalTimeMillis(),
                    pathStep.getDepartureTimeMillis()
            );
        }
    }
}
