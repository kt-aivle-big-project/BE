package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.dto.response.TaskOperationWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        List<RobotSnapshotCommand> robots,
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
        robots = robots == null ? List.of() : List.copyOf(robots);
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

        Map<Long, ReoptimizationOptimizationRequest.TaskInput> tasksById =
                request.remainingTasks().stream()
                        .collect(Collectors.toMap(
                                ReoptimizationOptimizationRequest
                                        .TaskInput::taskId,
                                Function.identity()
                        ));

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
                request.robots().stream()
                        .map(RobotSnapshotCommand::from)
                        .toList(),
                response.taskPlans().stream()
                        .map(plan -> TaskPlanCommand.from(
                                plan,
                                tasksById.get(plan.taskId())
                        ))
                        .toList()
        );
    }

    public record RobotSnapshotCommand(
            Long robotId,
            Long currentNodeId,
            Double batteryLevel,
            String status,
            Long currentTaskId,
            String runtimePhase,
            ReoptimizationOptimizationRequest.RemainingStage remainingStage
    ) {

        private static RobotSnapshotCommand from(
                ReoptimizationOptimizationRequest.RobotStateInput robot
        ) {
            return new RobotSnapshotCommand(
                    robot.robotId(),
                    robot.currentNodeId(),
                    robot.batteryLevel(),
                    robot.status(),
                    robot.currentTaskId(),
                    robot.runtimePhase(),
                    robot.remainingStage()
            );
        }
    }

    public record TaskPlanCommand(
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
            OperationWindowCommand pickingWindow,
            OperationWindowCommand droppingWindow,
            List<PathStepCommand> pathSteps
    ) {

        public TaskPlanCommand {
            pathSteps = pathSteps == null
                    ? List.of()
                    : List.copyOf(pathSteps);
        }

        public TaskPlanCommand(
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
                List<PathStepCommand> pathSteps
        ) {
            this(
                    robotId, taskId, sequence, executionStage,
                    snapshotAssignedRobotId, snapshotTaskStatus,
                    startNodeId, endNodeId, estimatedStartTimeMillis,
                    estimatedCompletionTimeMillis, null, null, pathSteps
            );
        }

        private static TaskPlanCommand from(
                TaskPlan plan,
                ReoptimizationOptimizationRequest.TaskInput task
        ) {
            Objects.requireNonNull(
                    task,
                    "Validated task plan must have a request task snapshot"
            );
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
                    task.currentlyAssignedRobotId(),
                    task.status(),
                    task.startNodeId(),
                    task.endNodeId(),
                    plan.estimatedStartTimeMillis(),
                    plan.estimatedCompletionTimeMillis(),
                    OperationWindowCommand.from(plan.pickingWindow()),
                    OperationWindowCommand.from(plan.droppingWindow()),
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

    public record OperationWindowCommand(
            Long nodeId,
            Long startTimeMillis,
            Long endTimeMillis
    ) {
        private static OperationWindowCommand from(
                TaskOperationWindow window
        ) {
            return window == null ? null : new OperationWindowCommand(
                    window.nodeId(),
                    window.startTimeMillis(),
                    window.endTimeMillis()
            );
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
