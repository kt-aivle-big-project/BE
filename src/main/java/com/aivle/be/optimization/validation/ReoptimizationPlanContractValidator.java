package com.aivle.be.optimization.validation;

import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 2-1에서 확정한 요청 snapshot과 AI 계획 사이의 기본 계약 검증기.
 * 경로 탐색이나 Runtime/DB 계획 적용은 수행하지 않는다.
 */
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
        rejectDuplicateTasks(response);

        for (TaskPlan plan : response.taskPlans()) {
            ReoptimizationOptimizationRequest.TaskInput task =
                    tasksById.get(plan.taskId());

            if (task == null) {
                throw new IllegalArgumentException(
                        "Task plan references an unknown remaining task"
                );
            }

            ReoptimizationOptimizationRequest.RobotStateInput robot =
                    robotsById.get(plan.robotId());

            if (robot == null || isUnavailable(robot)) {
                throw new IllegalArgumentException(
                        "Task plan references an unavailable robot"
                );
            }

            long firstArrival = plan.pathToStart().isEmpty()
                    ? plan.pathToEnd().get(0).arrivalTimeMillis()
                    : plan.pathToStart().get(0).arrivalTimeMillis();

            if (firstArrival < request.simulationClockMillis()) {
                throw new IllegalArgumentException(
                        "Task plan starts before the snapshot clock"
                );
            }

            if (plan.executionStage() == TaskPlan.ExecutionStage.TO_END) {
                validateToEndContinuation(robot, plan);
            }

            validateTaskEndpoints(robot, task, plan);
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

    private static void rejectDuplicateTasks(
            ReoptimizationResponse response
    ) {
        long distinctTaskCount = response.taskPlans().stream()
                .map(TaskPlan::taskId)
                .distinct()
                .count();

        if (distinctTaskCount != response.taskPlans().size()) {
            throw new IllegalArgumentException(
                    "A task cannot appear in more than one task plan"
            );
        }
    }

    private static void validateToEndContinuation(
            ReoptimizationOptimizationRequest.RobotStateInput robot,
            TaskPlan plan
    ) {
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
