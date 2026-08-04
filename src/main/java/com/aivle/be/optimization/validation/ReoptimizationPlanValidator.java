package com.aivle.be.optimization.validation;

import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.dto.response.TaskOperationWindow;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI가 반환한 전체 계획을 불변 snapshot만으로 검증한다.
 * repository 조회, 경로 계산, 계획 수정은 수행하지 않는다.
 */
public final class ReoptimizationPlanValidator {

    private ReoptimizationPlanValidator() {
    }

    public static void validate(ReoptimizationPlanValidationInput input) {
        validateResponseStateAndTaskCoverage(input);
        validateBlockedEdgeIds(input);

        if (input.response().status()
                != ReoptimizationResponse.Status.SUCCEEDED) {
            return;
        }

        Map<Long, ReplanningSnapshot.RobotSnapshot> robotsById =
                input.replanningSnapshot().robots().stream()
                .collect(Collectors.toMap(
                        ReplanningSnapshot.RobotSnapshot::robotId,
                        Function.identity()
                ));
        Map<Long, List<TaskPlan>> plansByRobot = input.response()
                .taskPlans().stream()
                .collect(Collectors.groupingBy(TaskPlan::robotId));

        validateRobotAssignments(input);
        validateSequences(plansByRobot);
        validateRobotContinuity(plansByRobot, robotsById);
        validatePathsAndConflicts(input, plansByRobot);
    }

    private static void validateResponseStateAndTaskCoverage(
            ReoptimizationPlanValidationInput input
    ) {
        ReoptimizationResponse response = input.response();
        Set<Long> remainingTaskIds = input.request().remainingTasks()
                .stream()
                .map(ReoptimizationOptimizationRequest.TaskInput::taskId)
                .collect(Collectors.toSet());
        List<Long> plannedTaskIds = response.taskPlans().stream()
                .map(TaskPlan::taskId)
                .toList();
        Set<Long> distinctPlannedTaskIds = new HashSet<>(plannedTaskIds);
        Set<Long> unassignedTaskIds = new HashSet<>(
                response.unassignedTaskIds()
        );

        if (distinctPlannedTaskIds.size() != plannedTaskIds.size()
                || unassignedTaskIds.size()
                != response.unassignedTaskIds().size()
                || !remainingTaskIds.containsAll(distinctPlannedTaskIds)
                || !remainingTaskIds.containsAll(unassignedTaskIds)
                || distinctPlannedTaskIds.stream()
                .anyMatch(unassignedTaskIds::contains)) {
            fail(
                    ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID,
                    "Planned and unassigned tasks must be known and disjoint"
            );
        }

        if (response.status() == ReoptimizationResponse.Status.SUCCEEDED) {
            if (response.taskPlans().isEmpty()
                    || !response.unassignedTaskIds().isEmpty()
                    || !distinctPlannedTaskIds.equals(remainingTaskIds)) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID,
                        "SUCCEEDED must plan every remaining task exactly once"
                );
            }
            return;
        }

        if (!response.taskPlans().isEmpty()) {
            fail(
                    ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID,
                    "INFEASIBLE and FAILED cannot contain task plans"
            );
        }

        /*
         * INFEASIBLE.unassignedTaskIds는 현재 계약만으로 해결 불가능한 전체
         * 작업인지 단정할 수 없어 remaining task의 부분집합까지만 검증한다.
         */
    }

    private static void validateBlockedEdgeIds(
            ReoptimizationPlanValidationInput input
    ) {
        if (!input.warehouseEdgeIds().containsAll(
                input.request().blockedEdgeIds()
        )) {
            fail(
                    ErrorCode.REOPTIMIZATION_PLAN_BLOCKED_EDGE,
                    "A blocked edge does not belong to the warehouse"
            );
        }
    }

    private static void validateRobotAssignments(
            ReoptimizationPlanValidationInput input
    ) {
        for (TaskPlan plan : input.response().taskPlans()) {
            if (!input.participantRobotIds().contains(plan.robotId())
                    || input.unavailableRobotIds().contains(
                    plan.robotId()
            )) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID,
                        "Plan references a non-participant or unavailable robot"
                );
            }
        }
    }

    private static void validateSequences(
            Map<Long, List<TaskPlan>> plansByRobot
    ) {
        for (List<TaskPlan> robotPlans : plansByRobot.values()) {
            List<TaskPlan> sorted = sortedBySequence(robotPlans);

            for (int expected = 0; expected < sorted.size(); expected++) {
                if (sorted.get(expected).sequence() != expected) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_SEQUENCE_INVALID,
                            "Robot sequence must start at zero without gaps"
                    );
                }
            }
        }
    }

    private static void validateRobotContinuity(
            Map<Long, List<TaskPlan>> plansByRobot,
            Map<Long, ReplanningSnapshot.RobotSnapshot> robotsById
    ) {
        for (Map.Entry<Long, List<TaskPlan>> entry
                : plansByRobot.entrySet()) {
            ReplanningSnapshot.RobotSnapshot robot = robotsById.get(
                    entry.getKey()
            );

            if (robot == null) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID,
                        "Participant robot is missing from the runtime snapshot"
                );
            }

            List<TaskPlan> sorted = sortedBySequence(entry.getValue());
            TaskPlan first = sorted.get(0);
            Long firstNode = first.executionStage()
                    == TaskPlan.ExecutionStage.TO_END
                    ? first.pathToEnd().get(0).nodeId()
                    : first.pathToStart().get(0).nodeId();

            if (!robot.currentNodeId().equals(firstNode)) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                        "The first robot plan must start at the snapshot node"
                );
            }

            for (int index = 1; index < sorted.size(); index++) {
                TaskPlan previous = sorted.get(index - 1);
                TaskPlan current = sorted.get(index);

                if (current.pathToStart().isEmpty()) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                            "Only the first task can continue with TO_END"
                    );
                }

                Long previousEnd = last(previous.pathToEnd()).nodeId();
                Long currentStart = current.pathToStart().get(0).nodeId();

                if (!previousEnd.equals(currentStart)
                        || current.estimatedStartTimeMillis()
                        < previous.estimatedCompletionTimeMillis()) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                            "Consecutive robot tasks are not continuous"
                    );
                }
            }
        }
    }

    private static void validatePathsAndConflicts(
            ReoptimizationPlanValidationInput input,
            Map<Long, List<TaskPlan>> plansByRobot
    ) {
        Map<NodePair, List<ReoptimizationPlanValidationInput.DirectedEdge>>
                edgesByPair = input.directedEdges().stream()
                .collect(Collectors.groupingBy(edge -> new NodePair(
                        edge.fromNodeId(),
                        edge.toNodeId()
                )));
        Set<Long> blockedEdgeIds = Set.copyOf(
                input.request().blockedEdgeIds()
        );
        List<NodeOccupancy> nodeOccupancies = new ArrayList<>();
        List<EdgeOccupancy> edgeOccupancies = new ArrayList<>();

        for (Map.Entry<Long, List<TaskPlan>> entry
                : plansByRobot.entrySet()) {
            Long robotId = entry.getKey();
            List<List<PathStep>> segments = new ArrayList<>();

            for (TaskPlan plan : sortedBySequence(entry.getValue())) {
                addOperationOccupancy(
                        input,
                        robotId,
                        plan.pickingWindow(),
                        nodeOccupancies
                );
                addOperationOccupancy(
                        input,
                        robotId,
                        plan.droppingWindow(),
                        nodeOccupancies
                );
                if (!plan.pathToStart().isEmpty()) {
                    segments.add(plan.pathToStart());
                }
                segments.add(plan.pathToEnd());
            }

            PathStep previousSegmentEnd = null;
            for (List<PathStep> segment : segments) {
                if (previousSegmentEnd != null) {
                    PathStep currentStart = segment.get(0);
                    validateStationaryBoundary(
                            robotId,
                            previousSegmentEnd,
                            currentStart,
                            nodeOccupancies
                    );
                }

                validatePathSegment(
                        input,
                        robotId,
                        segment,
                        edgesByPair,
                        blockedEdgeIds,
                        nodeOccupancies,
                        edgeOccupancies
                );
                previousSegmentEnd = last(segment);
            }
        }

        validateNodeConflicts(nodeOccupancies);
        validateEdgeConflicts(edgeOccupancies);
    }

    private static void addOperationOccupancy(
            ReoptimizationPlanValidationInput input,
            Long robotId,
            TaskOperationWindow window,
            List<NodeOccupancy> occupancies
    ) {
        if (window == null) {
            return;
        }
        if (!input.validNodeIds().contains(window.nodeId())) {
            fail(
                    ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                    "Operation window references a node outside the warehouse"
            );
        }
        occupancies.add(new NodeOccupancy(
                robotId,
                window.nodeId(),
                window.startTimeMillis(),
                window.endTimeMillis()
        ));
    }

    private static void validatePathSegment(
            ReoptimizationPlanValidationInput input,
            Long robotId,
            List<PathStep> path,
            Map<NodePair, List<ReoptimizationPlanValidationInput.DirectedEdge>>
                    edgesByPair,
            Set<Long> blockedEdgeIds,
            List<NodeOccupancy> nodeOccupancies,
            List<EdgeOccupancy> edgeOccupancies
    ) {
        for (PathStep step : path) {
            if (!input.validNodeIds().contains(step.nodeId())) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                        "Path references a node outside the warehouse"
                );
            }
            nodeOccupancies.add(new NodeOccupancy(
                    robotId,
                    step.nodeId(),
                    step.arrivalTimeMillis(),
                    step.departureTimeMillis()
            ));
        }

        for (int index = 1; index < path.size(); index++) {
            PathStep from = path.get(index - 1);
            PathStep to = path.get(index);

            if (from.nodeId().equals(to.nodeId())) {
                boolean representsWait = from.departureTimeMillis()
                        > from.arrivalTimeMillis()
                        || to.departureTimeMillis()
                        > to.arrivalTimeMillis();

                if (!representsWait) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                            "Repeated nodes are only valid for an explicit wait"
                    );
                }
                addStationaryGap(
                        robotId,
                        from,
                        to,
                        nodeOccupancies
                );
                continue;
            }

            if (to.arrivalTimeMillis() <= from.departureTimeMillis()) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                        "Movement between different nodes requires positive time"
                );
            }

            List<ReoptimizationPlanValidationInput.DirectedEdge> matches =
                    edgesByPair.getOrDefault(
                            new NodePair(from.nodeId(), to.nodeId()),
                            List.of()
                    );

            if (matches.size() != 1) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                        "Path node pair is disconnected or edge-ambiguous"
                );
            }

            ReoptimizationPlanValidationInput.DirectedEdge edge =
                    matches.get(0);
            if (blockedEdgeIds.contains(edge.edgeId())) {
                fail(
                        ErrorCode.REOPTIMIZATION_PLAN_BLOCKED_EDGE,
                        "Path traverses a blocked warehouse edge"
                );
            }

            edgeOccupancies.add(new EdgeOccupancy(
                    robotId,
                    edge.edgeId(),
                    from.nodeId(),
                    to.nodeId(),
                    from.departureTimeMillis(),
                    to.arrivalTimeMillis()
            ));
        }
    }

    private static void validateStationaryBoundary(
            Long robotId,
            PathStep previous,
            PathStep current,
            List<NodeOccupancy> nodeOccupancies
    ) {
        if (!previous.nodeId().equals(current.nodeId())
                || current.arrivalTimeMillis()
                < previous.departureTimeMillis()) {
            fail(
                    ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID,
                    "Path segments do not preserve robot position and time"
            );
        }

        addStationaryGap(robotId, previous, current, nodeOccupancies);
    }

    private static void addStationaryGap(
            Long robotId,
            PathStep previous,
            PathStep current,
            List<NodeOccupancy> nodeOccupancies
    ) {
        if (current.arrivalTimeMillis()
                > previous.departureTimeMillis()) {
            nodeOccupancies.add(new NodeOccupancy(
                    robotId,
                    previous.nodeId(),
                    previous.departureTimeMillis(),
                    current.arrivalTimeMillis()
            ));
        }
    }

    /**
     * 안전 우선 정책: 노드 점유 구간을 닫힌 구간으로 보고 동일 시각 교대도 충돌이다.
     */
    private static void validateNodeConflicts(
            List<NodeOccupancy> occupancies
    ) {
        for (int left = 0; left < occupancies.size(); left++) {
            for (int right = left + 1;
                    right < occupancies.size(); right++) {
                NodeOccupancy first = occupancies.get(left);
                NodeOccupancy second = occupancies.get(right);

                if (!first.robotId().equals(second.robotId())
                        && first.nodeId().equals(second.nodeId())
                        && overlapsClosed(
                        first.startMillis(),
                        first.endMillis(),
                        second.startMillis(),
                        second.endMillis()
                )) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_CONFLICT,
                            "Robots occupy the same node at overlapping times"
                    );
                }
            }
        }
    }

    /**
     * 같은 physical edge의 동일·반대 방향 이동을 모두 단일 점유 자원으로 취급한다.
     */
    private static void validateEdgeConflicts(
            List<EdgeOccupancy> occupancies
    ) {
        for (int left = 0; left < occupancies.size(); left++) {
            for (int right = left + 1;
                    right < occupancies.size(); right++) {
                EdgeOccupancy first = occupancies.get(left);
                EdgeOccupancy second = occupancies.get(right);

                if (!first.robotId().equals(second.robotId())
                        && first.edgeId().equals(second.edgeId())
                        && overlapsOpenEnd(
                        first.startMillis(),
                        first.endMillis(),
                        second.startMillis(),
                        second.endMillis()
                )) {
                    fail(
                            ErrorCode.REOPTIMIZATION_PLAN_CONFLICT,
                            "Robots traverse the same edge at overlapping times"
                    );
                }
            }
        }
    }

    private static boolean overlapsClosed(
            long firstStart,
            long firstEnd,
            long secondStart,
            long secondEnd
    ) {
        return Math.max(firstStart, secondStart)
                <= Math.min(firstEnd, secondEnd);
    }

    private static boolean overlapsOpenEnd(
            long firstStart,
            long firstEnd,
            long secondStart,
            long secondEnd
    ) {
        return Math.max(firstStart, secondStart)
                < Math.min(firstEnd, secondEnd);
    }

    private static List<TaskPlan> sortedBySequence(
            List<TaskPlan> plans
    ) {
        return plans.stream()
                .sorted(Comparator.comparingInt(TaskPlan::sequence))
                .toList();
    }

    private static PathStep last(List<PathStep> path) {
        return path.get(path.size() - 1);
    }

    private static void fail(ErrorCode errorCode, String message) {
        throw new ReoptimizationPlanValidationException(
                errorCode,
                message
        );
    }

    private record NodePair(Long fromNodeId, Long toNodeId) {
    }

    private record NodeOccupancy(
            Long robotId,
            Long nodeId,
            long startMillis,
            long endMillis
    ) {
    }

    private record EdgeOccupancy(
            Long robotId,
            Long edgeId,
            Long fromNodeId,
            Long toNodeId,
            long startMillis,
            long endMillis
    ) {
    }
}
