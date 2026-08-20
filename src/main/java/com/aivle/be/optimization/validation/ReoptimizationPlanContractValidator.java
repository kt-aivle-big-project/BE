package com.aivle.be.optimization.validation;

import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.dto.response.TaskOperationWindow;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ReoptimizationPlanContractValidator {

    private ReoptimizationPlanContractValidator() {
    }

    public static void validate(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response
    ) {
        if (response.status()
                != ReoptimizationResponse.Status.SUCCEEDED) {
            return;
        }

        Map<Long, ReoptimizationOptimizationRequest.RobotStateInput>
                robotsById = request.robots().stream()
                .collect(Collectors.toMap(
                        ReoptimizationOptimizationRequest
                                .RobotStateInput::robotId,
                        Function.identity()
                ));
        Map<Long, ReoptimizationOptimizationRequest.TaskInput> tasksById =
                request.remainingTasks().stream()
                .collect(Collectors.toMap(
                        ReoptimizationOptimizationRequest.TaskInput::taskId,
                        Function.identity()
                ));

        rejectSucceededPlanForStrandedFailedRobotTask(request);

        for (TaskPlan plan : response.taskPlans()) {
            ReoptimizationOptimizationRequest.TaskInput task =
                    tasksById.get(plan.taskId());

            ReoptimizationOptimizationRequest.RobotStateInput robot =
                    robotsById.get(plan.robotId());

            long firstArrival = plan.pathToStart().isEmpty()
                    ? plan.pathToEnd().get(0).arrivalTimeMillis()
                    : plan.pathToStart().get(0).arrivalTimeMillis();

            if (firstArrival < request.simulationClockMillis()) {
                throw new IllegalArgumentException(
                        "Task plan starts before the snapshot clock"
                );
            }

            if (robot != null
                    && plan.executionStage()
                    == TaskPlan.ExecutionStage.TO_END) {
                validateToEndContinuation(robot, plan);
            }

            if (robot != null && task != null) {
                validateTaskEndpoints(robot, task, plan);
                validateOperationWindows(request, plan);
            }
        }
    }

    private static void validateOperationWindows(
            ReoptimizationOptimizationRequest request,
            TaskPlan plan
    ) {
        if (request.pickingDurationMillis() == null
                || request.droppingDurationMillis() == null) {
            return;
        }
        TaskOperationWindow picking = plan.pickingWindow();
        TaskOperationWindow dropping = plan.droppingWindow();

        if (dropping == null) {
            throw new IllegalArgumentException(
                    "Every task plan requires droppingWindow"
            );
        }
        validateWindow(
                dropping,
                request.droppingDurationMillis(),
                request.simulationClockMillis(),
                "droppingWindow"
        );

        if (plan.executionStage() == TaskPlan.ExecutionStage.FULL) {
            if (picking == null) {
                throw new IllegalArgumentException(
                        "FULL task plan requires pickingWindow"
                );
            }
            validateWindow(
                    picking,
                    request.pickingDurationMillis(),
                    request.simulationClockMillis(),
                    "pickingWindow"
            );
            var lastToStart = plan.pathToStart().get(
                    plan.pathToStart().size() - 1
            );
            var firstToEnd = plan.pathToEnd().get(0);
            if (!picking.nodeId().equals(lastToStart.nodeId())
                    || !picking.startTimeMillis().equals(
                    lastToStart.departureTimeMillis()
            )
                    || !picking.nodeId().equals(firstToEnd.nodeId())
                    || !picking.endTimeMillis().equals(
                    firstToEnd.arrivalTimeMillis()
            )) {
                throw new IllegalArgumentException(
                        "Picking window must connect pathToStart and pathToEnd"
                );
            }
        } else if (picking != null) {
            throw new IllegalArgumentException(
                    "TO_END task plan must not have pickingWindow"
            );
        }

        var lastToEnd = plan.pathToEnd().get(
                plan.pathToEnd().size() - 1
        );
        if (!dropping.nodeId().equals(lastToEnd.nodeId())
                || !dropping.startTimeMillis().equals(
                lastToEnd.departureTimeMillis()
        )
                || !dropping.endTimeMillis().equals(
                plan.estimatedCompletionTimeMillis()
        )) {
            throw new IllegalArgumentException(
                    "Dropping window must follow pathToEnd and end the task"
            );
        }
    }

    private static void validateWindow(
            TaskOperationWindow window,
            Long requiredDurationMillis,
            Long simulationClockMillis,
            String fieldName
    ) {
        if (window.startTimeMillis() < simulationClockMillis
                || window.endTimeMillis() - window.startTimeMillis()
                != requiredDurationMillis) {
            throw new IllegalArgumentException(
                    fieldName + " must use the requested positive duration"
            );
        }
    }

    private static void rejectSucceededPlanForStrandedFailedRobotTask(
            ReoptimizationOptimizationRequest request
    ) {
        boolean strandedTaskExists = request.robots().stream()
                .filter(ReoptimizationPlanContractValidator::isUnavailable)
                .filter(robot -> robot.currentTaskId() != null)
                .anyMatch(robot ->
                        robot.remainingStage()
                                == ReoptimizationOptimizationRequest
                                .RemainingStage.TO_END
                                || robot.remainingStage()
                                == ReoptimizationOptimizationRequest
                                .RemainingStage.DROPPING
                );

        if (strandedTaskExists) {
            throw new IllegalArgumentException(
                    "A failed robot task after picking requires INFEASIBLE"
            );
        }
    }

    private static void validateToEndContinuation(
            ReoptimizationOptimizationRequest.RobotStateInput robot,
            TaskPlan plan
    ) {
        if (plan.sequence() != 0) {
            throw new IllegalArgumentException(
                    "TO_END must be the robot's first task with sequence zero"
            );
        }

        boolean sameInProgressTask = plan.taskId().equals(
                robot.currentTaskId()
        );
        boolean isToEndSnapshot = robot.remainingStage()
                == ReoptimizationOptimizationRequest.RemainingStage.TO_END;

        if (!sameInProgressTask || !isToEndSnapshot) {
            throw new IllegalArgumentException(
                    "TO_END is only valid for the same robot and task"
            );
        }
    }

    private static void validateTaskEndpoints(
            ReoptimizationOptimizationRequest.RobotStateInput robot,
            ReoptimizationOptimizationRequest.TaskInput task,
            TaskPlan plan
    ) {
        if (plan.executionStage() == TaskPlan.ExecutionStage.FULL) {
            Long pathToStartOrigin = plan.pathToStart().get(0).nodeId();
            Long pathToStartDestination = plan.pathToStart()
                    .get(plan.pathToStart().size() - 1)
                    .nodeId();
            Long pathToEndOrigin = plan.pathToEnd().get(0).nodeId();

            if (plan.sequence() == 0
                    && !robot.currentNodeId().equals(pathToStartOrigin)) {
                throw new IllegalArgumentException(
                        "The first FULL path must start at the snapshot robot node"
                );
            }

            if (!task.startNodeId().equals(pathToStartDestination)
                    || !task.startNodeId().equals(pathToEndOrigin)) {
                throw new IllegalArgumentException(
                        "FULL paths must include the task start node"
                );
            }
        } else if (!robot.currentNodeId().equals(
                plan.pathToEnd().get(0).nodeId()
        )) {
            throw new IllegalArgumentException(
                    "TO_END path must start at the snapshot robot node"
            );
        }

        Long pathDestination = plan.pathToEnd()
                .get(plan.pathToEnd().size() - 1)
                .nodeId();

        if (!task.endNodeId().equals(pathDestination)) {
            throw new IllegalArgumentException(
                    "pathToEnd must include the task end node"
            );
        }
    }

    private static boolean isUnavailable(
            ReoptimizationOptimizationRequest.RobotStateInput robot
    ) {
        return "ERROR".equals(robot.status())
                || "OFFLINE".equals(robot.status());
    }
}
