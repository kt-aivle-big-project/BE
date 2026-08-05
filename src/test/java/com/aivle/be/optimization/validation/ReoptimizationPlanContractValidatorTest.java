package com.aivle.be.optimization.validation;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationPlanContractValidatorTest {

    @Test
    void rejectsPlanStartingBeforeSnapshotClock() {
        ReoptimizationOptimizationRequest request = request(
                List.of(normalRobot(10L, 100L, stage("TO_START")))
        );
        ReoptimizationResponse response = succeeded(
                fullPlan(10L, 100L, 999L)
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot clock");
    }

    @Test
    void rejectsToEndWithNonEmptyPathToStart() {
        assertThatThrownBy(() -> new TaskPlan(
                10L,
                100L,
                0,
                TaskPlan.ExecutionStage.TO_END,
                List.of(step(10L, 1_000L)),
                List.of(step(10L, 1_000L), step(30L, 3_000L)),
                1_000L,
                3_000L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty pathToStart");
    }

    @Test
    void rejectsToEndReassignmentToAnotherRobot() {
        ReoptimizationOptimizationRequest request = request(List.of(
                normalRobot(10L, 100L, stage("TO_END")),
                normalRobot(11L, null, stage("IDLE"))
        ));
        ReoptimizationResponse response = succeeded(
                toEndPlan(11L, 100L)
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same robot and task");
    }

    @Test
    void allowsSameRobotToContinueToEnd() {
        ReoptimizationOptimizationRequest request = request(List.of(
                normalRobot(10L, 100L, stage("TO_END"))
        ));
        ReoptimizationResponse response = succeeded(
                toEndPlan(10L, 100L)
        );

        assertThatCode(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsToEndWhenSequenceIsNotZero() {
        ReoptimizationOptimizationRequest request = request(List.of(
                normalRobot(10L, 100L, stage("TO_END"))
        ));
        ReoptimizationResponse response = succeeded(
                toEndPlan(10L, 100L, 1)
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence zero");
    }

    @Test
    void rejectsToEndWithWrongSnapshotOriginOrTaskDestination() {
        ReoptimizationOptimizationRequest request = request(List.of(
                normalRobot(10L, 100L, stage("TO_END"))
        ));
        TaskPlan wrongOrigin = toEndPlan(
                10L,
                100L,
                0,
                11L,
                30L
        );
        TaskPlan wrongDestination = toEndPlan(
                10L,
                100L,
                0,
                10L,
                31L
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        succeeded(wrongOrigin)
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot robot node");
        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        succeeded(wrongDestination)
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task end node");
    }

    @Test
    void rejectsSucceededReassignmentAfterFailedRobotCompletedPicking() {
        ReoptimizationOptimizationRequest request = request(List.of(
                failedRobot(10L, 100L, stage("TO_END")),
                normalRobot(11L, null, stage("IDLE"))
        ));
        ReoptimizationResponse response = succeeded(
                fullPlan(11L, 100L, 1_000L)
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires INFEASIBLE");
    }

    @Test
    void allowsFullReassignmentBeforeFailedRobotCompletesPicking() {
        ReoptimizationOptimizationRequest request = request(List.of(
                failedRobot(10L, 100L, stage("PICKING")),
                normalRobot(11L, null, stage("IDLE"))
        ));
        ReoptimizationResponse response = succeeded(
                fullPlan(11L, 100L, 1_000L)
        );

        assertThatCode(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void acceptsInfeasibleForFailedRobotTaskAfterPicking() {
        ReoptimizationOptimizationRequest request = request(List.of(
                failedRobot(10L, 100L, stage("DROPPING")),
                normalRobot(11L, null, stage("IDLE"))
        ));
        ReoptimizationResponse response = new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                ReoptimizationResponse.Status.INFEASIBLE,
                List.of(),
                List.of(100L),
                "handover is not supported"
        );

        assertThatCode(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).doesNotThrowAnyException();
    }

    @Test
    void rejectsSucceededPlanWhenOfflineRobotHoldsTheTask() {
        ReoptimizationOptimizationRequest request = request(List.of(
                robot(10L, 100L, "OFFLINE", stage("DROPPING")),
                normalRobot(11L, null, stage("IDLE"))
        ));
        ReoptimizationResponse response = succeeded(
                fullPlan(11L, 100L, 1_000L)
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanContractValidator.validate(
                        request,
                        response
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires INFEASIBLE");
    }

    private ReoptimizationOptimizationRequest request(
            List<ReoptimizationOptimizationRequest.RobotStateInput> robots
    ) {
        return new ReoptimizationOptimizationRequest(
                "replan-1",
                1L,
                7L,
                1_000L,
                1L,
                ReoptimizationReason.ROBOT_FAILURE,
                10L,
                List.of(),
                "contract test",
                robots,
                List.of(new ReoptimizationOptimizationRequest.TaskInput(
                        100L,
                        10L,
                        20L,
                        30L,
                        "OUTBOUND",
                        "IN_PROGRESS"
                ))
        );
    }

    private ReoptimizationOptimizationRequest.RobotStateInput normalRobot(
            Long robotId,
            Long taskId,
            ReoptimizationOptimizationRequest.RemainingStage remainingStage
    ) {
        return robot(robotId, taskId, "PAUSED", remainingStage);
    }

    private ReoptimizationOptimizationRequest.RobotStateInput failedRobot(
            Long robotId,
            Long taskId,
            ReoptimizationOptimizationRequest.RemainingStage remainingStage
    ) {
        return robot(robotId, taskId, "ERROR", remainingStage);
    }

    private ReoptimizationOptimizationRequest.RobotStateInput robot(
            Long robotId,
            Long taskId,
            String status,
            ReoptimizationOptimizationRequest.RemainingStage remainingStage
    ) {
        return new ReoptimizationOptimizationRequest.RobotStateInput(
                robotId,
                robotId,
                80.0,
                status,
                taskId,
                remainingStage == stage("PICKING")
                        ? "PICKING"
                        : "MOVING_TO_END",
                remainingStage
        );
    }

    private ReoptimizationOptimizationRequest.RemainingStage stage(
            String name
    ) {
        return ReoptimizationOptimizationRequest.RemainingStage.valueOf(
                name
        );
    }

    private ReoptimizationResponse succeeded(TaskPlan plan) {
        return new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                ReoptimizationResponse.Status.SUCCEEDED,
                List.of(plan),
                List.of(),
                null
        );
    }

    private TaskPlan fullPlan(
            Long robotId,
            Long taskId,
            long startTimeMillis
    ) {
        return new TaskPlan(
                robotId,
                taskId,
                0,
                TaskPlan.ExecutionStage.FULL,
                List.of(
                        step(robotId, startTimeMillis),
                        step(20L, startTimeMillis + 1_000L)
                ),
                List.of(
                        step(20L, startTimeMillis + 1_000L),
                        step(30L, startTimeMillis + 2_000L)
                ),
                startTimeMillis,
                startTimeMillis + 2_000L
        );
    }

    private TaskPlan toEndPlan(Long robotId, Long taskId) {
        return toEndPlan(robotId, taskId, 0);
    }

    private TaskPlan toEndPlan(
            Long robotId,
            Long taskId,
            int sequence
    ) {
        return toEndPlan(
                robotId,
                taskId,
                sequence,
                robotId,
                30L
        );
    }

    private TaskPlan toEndPlan(
            Long robotId,
            Long taskId,
            int sequence,
            Long originNodeId,
            Long destinationNodeId
    ) {
        return new TaskPlan(
                robotId,
                taskId,
                sequence,
                TaskPlan.ExecutionStage.TO_END,
                List.of(),
                List.of(
                        step(originNodeId, 1_000L),
                        step(destinationNodeId, 3_000L)
                ),
                1_000L,
                3_000L
        );
    }

    private PathStep step(Long nodeId, Long timeMillis) {
        return new PathStep(nodeId, timeMillis, timeMillis);
    }
}
