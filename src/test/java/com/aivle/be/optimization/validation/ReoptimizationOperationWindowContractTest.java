package com.aivle.be.optimization.validation;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskOperationWindow;
import com.aivle.be.optimization.dto.response.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationOperationWindowContractTest {

    @Test
    void acceptsFullPlanWithExactOperationWindows() {
        assertThatCode(() -> validate(plan(
                new TaskOperationWindow(20L, 2_000L, 2_100L),
                new TaskOperationWindow(30L, 3_100L, 3_300L),
                2_100L
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingPickingWindow() {
        assertThatThrownBy(() -> validate(plan(
                null,
                new TaskOperationWindow(30L, 3_100L, 3_300L),
                2_100L
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pickingWindow");
    }

    @Test
    void rejectsWrongPickingDuration() {
        assertThatThrownBy(() -> validate(plan(
                new TaskOperationWindow(20L, 2_000L, 2_101L),
                new TaskOperationWindow(30L, 3_101L, 3_301L),
                2_101L
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested positive duration");
    }

    @Test
    void rejectsGapBetweenPickingAndPathToEnd() {
        assertThatThrownBy(() -> validate(plan(
                new TaskOperationWindow(20L, 2_000L, 2_100L),
                new TaskOperationWindow(30L, 3_100L, 3_300L),
                2_101L
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect pathToStart");
    }

    private void validate(TaskPlan plan) {
        ReoptimizationPlanContractValidator.validate(
                request(),
                new ReoptimizationResponse(
                        "request", "replan", 1L, 1L,
                        ReoptimizationResponse.Status.SUCCEEDED,
                        List.of(plan), List.of(), null
                )
        );
    }

    private TaskPlan plan(
            TaskOperationWindow picking,
            TaskOperationWindow dropping,
            long pathToEndStart
    ) {
        return new TaskPlan(
                10L, 100L, 0, TaskPlan.ExecutionStage.FULL,
                List.of(step(10L, 1_000L), step(20L, 2_000L)),
                List.of(step(20L, pathToEndStart), step(30L, 3_100L)),
                picking, dropping, 1_000L, dropping.endTimeMillis()
        );
    }

    private PathStep step(Long nodeId, long time) {
        return new PathStep(nodeId, time, time);
    }

    private ReoptimizationOptimizationRequest request() {
        return new ReoptimizationOptimizationRequest(
                "replan", 1L, 1L, 1_000L, 1L,
                ReoptimizationReason.ROBOT_FAILURE, 99L, List.of(), null,
                100L, 200L,
                List.of(new ReoptimizationOptimizationRequest.RobotStateInput(
                        10L, 10L, 100.0, "PAUSED", null,
                        "IDLE", ReoptimizationOptimizationRequest.RemainingStage.IDLE
                )),
                List.of(new ReoptimizationOptimizationRequest.TaskInput(
                        100L, 10L, 20L, 30L,
                        "OUTBOUND", "ASSIGNED"
                ))
        );
    }
}
