package com.aivle.be.optimization.validation;

import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationPlanValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsEveryRemainingTaskExactlyOnce() {
        Scenario scenario = baseScenario();

        assertValid(scenario, validMultipleRobotResponse());
    }

    @Test
    void rejectsMissingDuplicateAndUnknownTasks() {
        Scenario scenario = baseScenario();
        TaskPlan task100 = standardTask100(10L, 0, 1_000L);

        assertInvalid(
                scenario,
                succeeded(List.of(task100)),
                ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID
        );
        assertInvalid(
                scenario,
                succeeded(List.of(
                        task100,
                        standardTask100(11L, 0, 4_000L)
                )),
                ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID
        );
        assertInvalid(
                scenario,
                succeeded(List.of(
                        plan(10L, 999L, 0,
                                path(1_000L, 10L, 20L),
                                path(2_000L, 20L, 30L)),
                        standardTask101(11L, 0, 4_000L)
                )),
                ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID
        );
    }

    @Test
    void rejectsUnknownAndFailedRobotAssignments() {
        Scenario scenario = baseScenario();

        assertInvalid(
                scenario,
                succeeded(List.of(
                        standardTask100(999L, 0, 1_000L),
                        standardTask101(11L, 0, 4_000L)
                )),
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );
        assertInvalid(
                scenario,
                succeeded(List.of(
                        plan(12L, 100L, 0,
                                path(1_000L, 50L, 20L),
                                path(2_000L, 20L, 30L)),
                        standardTask101(11L, 0, 4_000L)
                )),
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );
    }

    @Test
    void validatesSequenceStartGapAndDuplicate() {
        Scenario oneTask = scenario(
                List.of(robot(10L, 10L, "PAUSED")),
                List.of(task(100L, 20L, 30L)),
                List.of()
        );
        assertInvalid(
                oneTask,
                succeeded(List.of(standardTask100(10L, 1, 1_000L))),
                ErrorCode.REOPTIMIZATION_PLAN_SEQUENCE_INVALID
        );

        Scenario twoTasks = oneRobotTwoTaskScenario();
        TaskPlan first = standardTask100(10L, 0, 1_000L);
        TaskPlan secondGap = task101AfterTask100(2, 3_000L);
        TaskPlan secondDuplicate = task101AfterTask100(0, 3_000L);

        assertInvalid(
                twoTasks,
                succeeded(List.of(first, secondGap)),
                ErrorCode.REOPTIMIZATION_PLAN_SEQUENCE_INVALID
        );
        assertInvalid(
                twoTasks,
                succeeded(List.of(first, secondDuplicate)),
                ErrorCode.REOPTIMIZATION_PLAN_SEQUENCE_INVALID
        );
    }

    @Test
    void validatesConsecutiveRobotTaskPositionAndTime() {
        Scenario scenario = oneRobotTwoTaskScenario();
        TaskPlan first = standardTask100(10L, 0, 1_000L);
        TaskPlan disconnectedPosition = plan(
                10L,
                101L,
                1,
                path(3_000L, 40L),
                path(3_000L, 40L, 50L)
        );
        TaskPlan timeRegression = task101AfterTask100(1, 2_500L);

        assertInvalid(
                scenario,
                succeeded(List.of(first, disconnectedPosition)),
                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
        );
        assertInvalid(
                scenario,
                succeeded(List.of(first, timeRegression)),
                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
        );
        assertValid(
                scenario,
                succeeded(List.of(first, task101AfterTask100(1, 3_000L)))
        );
    }

    @Test
    void rejectsUnknownNodeDisconnectedPairAndBlockedEdge()
            throws Exception {
        Scenario scenario = scenario(
                List.of(robot(10L, 10L, "PAUSED")),
                List.of(task(100L, 20L, 30L)),
                List.of()
        );
        TaskPlan unknownNode = plan(
                10L, 100L, 0,
                path(1_000L, 10L, 999L),
                path(2_000L, 999L, 30L)
        );
        TaskPlan disconnected = plan(
                10L, 100L, 0,
                path(1_000L, 10L, 20L),
                path(2_000L, 20L, 60L)
        );

        assertInvalid(
                scenario,
                succeeded(List.of(unknownNode)),
                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
        );
        assertInvalid(
                scenario,
                succeeded(List.of(disconnected)),
                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
        );

        Scenario blocked = scenario(
                scenario.request().robots(),
                scenario.request().remainingTasks(),
                List.of(2L)
        );
        assertInvalid(
                blocked,
                readFixture("invalid-blocked-edge.json"),
                ErrorCode.REOPTIMIZATION_PLAN_BLOCKED_EDGE
        );

        Scenario unknownBlockedEdge = scenario(
                scenario.request().robots(),
                scenario.request().remainingTasks(),
                List.of(999L)
        );
        assertInvalid(
                unknownBlockedEdge,
                response(
                        ReoptimizationResponse.Status.INFEASIBLE,
                        List.of(),
                        List.of(100L)
                ),
                ErrorCode.REOPTIMIZATION_PLAN_BLOCKED_EDGE
        );

        Scenario otherWarehouseBlockedEdge = scenario(
                scenario.request().robots(),
                scenario.request().remainingTasks(),
                List.of(8L)
        );
        assertInvalid(
                otherWarehouseBlockedEdge,
                response(
                        ReoptimizationResponse.Status.INFEASIBLE,
                        List.of(),
                        List.of(100L)
                ),
                ErrorCode.REOPTIMIZATION_PLAN_BLOCKED_EDGE
        );
    }

    @Test
    void keepsNumericBlockedEdgeIdsAndRejectsParallelEdgeAmbiguity() {
        Scenario scenario = scenario(
                List.of(robot(10L, 10L, "PAUSED")),
                List.of(task(100L, 20L, 30L)),
                List.of(2L)
        );
        assertThat(scenario.request().blockedEdgeIds())
                .containsExactly(2L);
        assertThat(scenario.request().blockedEdgeIds().get(0))
                .isInstanceOf(Long.class);

        List<ReoptimizationPlanValidationInput.DirectedEdge> parallelEdges =
                new java.util.ArrayList<>(directedEdges());
        parallelEdges.add(edge(8L, 10L, 20L));

        assertThatThrownBy(() -> ReoptimizationPlanValidator.validate(
                input(
                        scenario(
                                scenario.request().robots(),
                                scenario.request().remainingTasks(),
                                List.of()
                        ),
                        succeeded(List.of(
                                standardTask100(10L, 0, 1_000L)
                        )),
                        parallelEdges,
                        Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
                )
        )).isInstanceOfSatisfying(
                ReoptimizationPlanValidationException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
                        )
        );
    }

    @Test
    void appliesDirectedEdgeRules() {
        Scenario forward = scenario(
                List.of(robot(10L, 20L, "PAUSED")),
                List.of(task(100L, 30L, 30L)),
                List.of()
        );
        TaskPlan forwardPlan = plan(
                10L, 100L, 0,
                path(1_000L, 20L, 30L),
                path(2_000L, 30L)
        );
        assertValid(forward, succeeded(List.of(forwardPlan)));

        Scenario reverse = scenario(
                List.of(robot(10L, 30L, "PAUSED")),
                List.of(task(100L, 20L, 20L)),
                List.of()
        );
        TaskPlan reversePlan = plan(
                10L, 100L, 0,
                path(1_000L, 30L, 20L),
                path(2_000L, 20L)
        );
        assertInvalid(
                reverse,
                succeeded(List.of(reversePlan)),
                ErrorCode.REOPTIMIZATION_PLAN_PATH_INVALID
        );
    }

    @Test
    void rejectsNodeTimeConflictIncludingExactHandoff() {
        Scenario scenario = nodeConflictScenario();

        assertInvalid(
                scenario,
                nodeHandoffResponse(2_000L),
                ErrorCode.REOPTIMIZATION_PLAN_CONFLICT
        );
    }

    @Test
    void allowsNodeHandoffWhenTimesAreActuallySeparated() {
        assertValid(
                nodeConflictScenario(),
                nodeHandoffResponse(2_001L)
        );
    }

    @Test
    void rejectsHeadOnAndSameDirectionEdgeConflicts() {
        Scenario headOn = edgeConflictScenario(false);
        Scenario sameDirection = edgeConflictScenario(true);

        assertInvalid(
                headOn,
                edgeConflictResponse(false),
                ErrorCode.REOPTIMIZATION_PLAN_CONFLICT
        );
        assertInvalid(
                sameDirection,
                edgeConflictResponse(true),
                ErrorCode.REOPTIMIZATION_PLAN_CONFLICT
        );
    }

    @Test
    void allowsEdgeOccupancyIntervalsThatOnlyTouchAtBoundary() {
        Scenario scenario = scenario(
                List.of(
                        robot(10L, 10L, "PAUSED"),
                        robot(11L, 40L, "PAUSED")
                ),
                List.of(
                        task(100L, 20L, 20L),
                        task(101L, 20L, 20L)
                ),
                List.of()
        );
        TaskPlan first = plan(
                10L,
                100L,
                0,
                List.of(step(10L, 1_000L), step(20L, 2_000L)),
                List.of(step(20L, 2_000L))
        );
        TaskPlan second = plan(
                11L,
                101L,
                0,
                List.of(
                        step(40L, 1_000L),
                        step(10L, 2_000L),
                        step(20L, 3_000L)
                ),
                List.of(step(20L, 3_000L))
        );
        List<ReoptimizationPlanValidationInput.DirectedEdge> edges =
                new java.util.ArrayList<>(directedEdges());
        edges.add(edge(8L, 40L, 10L));

        assertThatCode(() -> ReoptimizationPlanValidator.validate(
                input(
                        scenario,
                        succeeded(List.of(first, second)),
                        edges,
                        Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
                )
        )).doesNotThrowAnyException();
    }

    @Test
    void acceptsConflictFreeMultipleRobotsAndMultipleTasks()
            throws Exception {
        assertValid(
                baseScenario(),
                readFixture("success-multiple-robots.json")
        );
        assertValid(
                oneRobotTwoTaskScenario(),
                readFixture("success-multiple-tasks-one-robot.json")
        );
    }

    @Test
    void validatesInfeasibleAndFailedPayloadShape() {
        Scenario scenario = baseScenario();
        ReoptimizationResponse infeasible = response(
                ReoptimizationResponse.Status.INFEASIBLE,
                List.of(),
                List.of(100L)
        );
        ReoptimizationResponse failedWithPlan = response(
                ReoptimizationResponse.Status.FAILED,
                List.of(standardTask100(10L, 0, 1_000L)),
                List.of()
        );

        assertValid(scenario, infeasible);
        assertInvalid(
                scenario,
                failedWithPlan,
                ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID
        );
    }

    private Scenario baseScenario() {
        return scenario(
                List.of(
                        robot(10L, 10L, "PAUSED"),
                        robot(11L, 40L, "PAUSED"),
                        robot(12L, 50L, "ERROR")
                ),
                List.of(
                        task(100L, 20L, 30L),
                        task(101L, 50L, 60L)
                ),
                List.of()
        );
    }

    private Scenario oneRobotTwoTaskScenario() {
        return scenario(
                List.of(robot(10L, 10L, "PAUSED")),
                List.of(
                        task(100L, 20L, 30L),
                        task(101L, 40L, 50L)
                ),
                List.of()
        );
    }

    private Scenario nodeConflictScenario() {
        return scenario(
                List.of(
                        robot(10L, 10L, "PAUSED"),
                        robot(11L, 40L, "PAUSED")
                ),
                List.of(
                        task(100L, 20L, 30L),
                        task(101L, 20L, 50L)
                ),
                List.of()
        );
    }

    private Scenario edgeConflictScenario(boolean sameDirection) {
        return scenario(
                List.of(
                        robot(10L, 10L, "PAUSED"),
                        robot(11L, sameDirection ? 10L : 20L, "PAUSED")
                ),
                List.of(
                        task(100L, 20L, 20L),
                        task(101L, sameDirection ? 20L : 10L,
                                sameDirection ? 20L : 10L)
                ),
                List.of()
        );
    }

    private Scenario scenario(
            List<ReoptimizationOptimizationRequest.RobotStateInput> robots,
            List<ReoptimizationOptimizationRequest.TaskInput> tasks,
            List<Long> blockedEdgeIds
    ) {
        ReoptimizationOptimizationRequest request =
                new ReoptimizationOptimizationRequest(
                        "replan-1",
                        1L,
                        7L,
                        1_000L,
                        1L,
                        ReoptimizationReason.MANUAL_REQUEST,
                        null,
                        blockedEdgeIds,
                        "validator test",
                        robots,
                        tasks
                );
        List<ReplanningSnapshot.RobotSnapshot> snapshots = robots.stream()
                .map(robot -> new ReplanningSnapshot.RobotSnapshot(
                        robot.robotId(),
                        robot.currentNodeId(),
                        robot.batteryLevel(),
                        RobotStatus.valueOf(robot.status()),
                        robot.currentTaskId(),
                        com.aivle.be.simulationrun.playback.RobotRuntime
                                .Phase.IDLE,
                        0L
                ))
                .toList();
        ReplanningSnapshot snapshot = new ReplanningSnapshot(
                "replan-1",
                1L,
                7L,
                1_000L,
                snapshots
        );
        Set<Long> participants = robots.stream()
                .map(ReoptimizationOptimizationRequest.RobotStateInput::robotId)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> unavailable = robots.stream()
                .filter(robot -> "ERROR".equals(robot.status())
                        || "OFFLINE".equals(robot.status()))
                .map(ReoptimizationOptimizationRequest.RobotStateInput::robotId)
                .collect(java.util.stream.Collectors.toSet());

        return new Scenario(request, snapshot, participants, unavailable);
    }

    private ReoptimizationResponse validMultipleRobotResponse() {
        return succeeded(List.of(
                standardTask100(10L, 0, 1_000L),
                standardTask101(11L, 0, 4_000L)
        ));
    }

    private ReoptimizationResponse nodeHandoffResponse(
            long secondArrival
    ) {
        TaskPlan first = plan(
                10L, 100L, 0,
                List.of(step(10L, 1_000L), step(20L, 2_000L)),
                List.of(step(20L, 2_000L), step(30L, 3_000L))
        );
        TaskPlan second = plan(
                11L, 101L, 0,
                List.of(step(40L, 1_000L), step(20L, secondArrival)),
                List.of(
                        step(20L, secondArrival),
                        step(50L, secondArrival + 1_000L)
                )
        );
        return succeeded(List.of(first, second));
    }

    private ReoptimizationResponse edgeConflictResponse(
            boolean sameDirection
    ) {
        TaskPlan first = plan(
                10L, 100L, 0,
                List.of(step(10L, 1_000L), step(20L, 3_000L)),
                List.of(step(20L, 3_000L))
        );
        TaskPlan second = plan(
                11L, 101L, 0,
                List.of(
                        step(sameDirection ? 10L : 20L, 1_500L),
                        step(sameDirection ? 20L : 10L, 2_500L)
                ),
                List.of(step(sameDirection ? 20L : 10L, 2_500L))
        );
        return succeeded(List.of(first, second));
    }

    private TaskPlan standardTask100(
            Long robotId,
            int sequence,
            long start
    ) {
        return plan(
                robotId, 100L, sequence,
                path(start, robotId.equals(10L) ? 10L : 40L, 20L),
                path(start + 1_000L, 20L, 30L)
        );
    }

    private TaskPlan standardTask101(
            Long robotId,
            int sequence,
            long start
    ) {
        return plan(
                robotId, 101L, sequence,
                path(start, 40L, 50L),
                path(start + 1_000L, 50L, 60L)
        );
    }

    private TaskPlan task101AfterTask100(int sequence, long start) {
        return plan(
                10L, 101L, sequence,
                path(start, 30L, 40L),
                path(start + 1_000L, 40L, 50L)
        );
    }

    private TaskPlan plan(
            Long robotId,
            Long taskId,
            int sequence,
            List<PathStep> pathToStart,
            List<PathStep> pathToEnd
    ) {
        return new TaskPlan(
                robotId,
                taskId,
                sequence,
                TaskPlan.ExecutionStage.FULL,
                pathToStart,
                pathToEnd,
                pathToStart.get(0).arrivalTimeMillis(),
                pathToEnd.get(pathToEnd.size() - 1)
                        .departureTimeMillis()
        );
    }

    private List<PathStep> path(long start, Long... nodeIds) {
        List<PathStep> path = new java.util.ArrayList<>();
        for (int index = 0; index < nodeIds.length; index++) {
            path.add(step(nodeIds[index], start + index * 1_000L));
        }
        return List.copyOf(path);
    }

    private PathStep step(Long nodeId, long time) {
        return new PathStep(nodeId, time, time);
    }

    private ReoptimizationOptimizationRequest.RobotStateInput robot(
            Long robotId,
            Long currentNodeId,
            String status
    ) {
        return new ReoptimizationOptimizationRequest.RobotStateInput(
                robotId,
                currentNodeId,
                80.0,
                status,
                null,
                "IDLE",
                ReoptimizationOptimizationRequest.RemainingStage.IDLE
        );
    }

    private ReoptimizationOptimizationRequest.TaskInput task(
            Long taskId,
            Long startNodeId,
            Long endNodeId
    ) {
        return new ReoptimizationOptimizationRequest.TaskInput(
                taskId,
                null,
                startNodeId,
                endNodeId,
                "OUTBOUND",
                "PENDING"
        );
    }

    private ReoptimizationResponse succeeded(List<TaskPlan> plans) {
        return response(
                ReoptimizationResponse.Status.SUCCEEDED,
                plans,
                List.of()
        );
    }

    private ReoptimizationResponse response(
            ReoptimizationResponse.Status status,
            List<TaskPlan> plans,
            List<Long> unassignedTaskIds
    ) {
        return new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                status,
                plans,
                unassignedTaskIds,
                null
        );
    }

    private void assertValid(
            Scenario scenario,
            ReoptimizationResponse response
    ) {
        assertThatCode(() -> ReoptimizationPlanValidator.validate(
                input(scenario, response)
        )).doesNotThrowAnyException();
    }

    private void assertInvalid(
            Scenario scenario,
            ReoptimizationResponse response,
            ErrorCode expectedErrorCode
    ) {
        assertThatThrownBy(() -> ReoptimizationPlanValidator.validate(
                input(scenario, response)
        )).isInstanceOfSatisfying(
                ReoptimizationPlanValidationException.class,
                exception -> org.assertj.core.api.Assertions
                        .assertThat(exception.getErrorCode())
                        .isEqualTo(expectedErrorCode)
        );
    }

    private ReoptimizationPlanValidationInput input(
            Scenario scenario,
            ReoptimizationResponse response
    ) {
        return input(
                scenario,
                response,
                directedEdges(),
                Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        );
    }

    private ReoptimizationPlanValidationInput input(
            Scenario scenario,
            ReoptimizationResponse response,
            List<ReoptimizationPlanValidationInput.DirectedEdge>
                    directedEdges,
            Set<Long> warehouseEdgeIds
    ) {
        return new ReoptimizationPlanValidationInput(
                scenario.snapshot(),
                scenario.request(),
                response,
                Set.of(10L, 20L, 30L, 40L, 50L, 60L, 70L),
                directedEdges,
                warehouseEdgeIds,
                scenario.participantRobotIds(),
                scenario.unavailableRobotIds()
        );
    }

    private List<ReoptimizationPlanValidationInput.DirectedEdge>
    directedEdges() {
        return List.of(
                edge(1L, 10L, 20L),
                edge(1L, 20L, 10L),
                edge(2L, 20L, 30L),
                edge(3L, 30L, 40L),
                edge(3L, 40L, 30L),
                edge(4L, 40L, 50L),
                edge(4L, 50L, 40L),
                edge(5L, 50L, 60L),
                edge(6L, 40L, 20L),
                edge(6L, 20L, 40L),
                edge(7L, 20L, 50L),
                edge(7L, 50L, 20L)
        );
    }

    private ReoptimizationPlanValidationInput.DirectedEdge edge(
            Long edgeId,
            Long from,
            Long to
    ) {
        return new ReoptimizationPlanValidationInput.DirectedEdge(
                edgeId,
                from,
                to
        );
    }

    private ReoptimizationResponse readFixture(String fixtureName)
            throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "fixtures/reoptimization/" + fixtureName
        );

        try (var inputStream = resource.getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    ReoptimizationResponse.class
            );
        }
    }

    private record Scenario(
            ReoptimizationOptimizationRequest request,
            ReplanningSnapshot snapshot,
            Set<Long> participantRobotIds,
            Set<Long> unavailableRobotIds
    ) {
    }
}
