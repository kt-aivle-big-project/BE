package com.aivle.be.optimization.staging;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationPlanStageCommandTest {

    @Test
    void convertsValidatedResponseWithoutLosingPlanData() {
        ReoptimizationPlanStageCommand command =
                ReoptimizationPlanStageCommand.from(
                        request(),
                        succeededResponse()
                );

        assertThat(command.simulationRunId()).isEqualTo(1L);
        assertThat(command.replanId()).isEqualTo("replan-1");
        assertThat(command.snapshotVersion()).isEqualTo(7L);
        assertThat(command.requestId()).isEqualTo("request-1");
        assertThat(command.simulationClockMillis()).isEqualTo(1_000L);
        assertThat(command.reason())
                .isEqualTo(ReoptimizationReason.OBSTACLE_DETECTED);
        assertThat(command.triggerRobotId()).isEqualTo(10L);
        assertThat(command.description()).isEqualTo("blocked edge");
        assertThat(command.responseMessage()).isEqualTo("validated plan");
        assertThat(command.blockedEdgeIds()).containsExactly(9L, 10L);
        assertThat(command.robots()).singleElement()
                .satisfies(robot -> {
                    assertThat(robot.robotId()).isEqualTo(10L);
                    assertThat(robot.currentTaskId()).isEqualTo(100L);
                    assertThat(robot.remainingStage()).isEqualTo(
                            ReoptimizationOptimizationRequest
                                    .RemainingStage.TO_START
                    );
                });
        assertThat(command.taskPlans()).singleElement()
                .satisfies(plan -> {
                    assertThat(plan.robotId()).isEqualTo(10L);
                    assertThat(plan.taskId()).isEqualTo(100L);
                    assertThat(plan.sequence()).isZero();
                    assertThat(plan.executionStage())
                            .isEqualTo(TaskPlan.ExecutionStage.FULL);
                    assertThat(plan.snapshotAssignedRobotId()).isNull();
                    assertThat(plan.snapshotTaskStatus())
                            .isEqualTo("PENDING");
                    assertThat(plan.startNodeId()).isEqualTo(20L);
                    assertThat(plan.endNodeId()).isEqualTo(30L);
                    assertThat(plan.estimatedStartTimeMillis())
                            .isEqualTo(1_000L);
                    assertThat(plan.estimatedCompletionTimeMillis())
                            .isEqualTo(4_000L);
                    assertThat(plan.pathSteps())
                            .extracting(
                                    ReoptimizationPlanStageCommand
                                            .PathStepCommand::segmentType
                            )
                            .containsExactly(
                                    ReoptimizationPlanStageCommand
                                            .SegmentType.TO_START,
                                    ReoptimizationPlanStageCommand
                                            .SegmentType.TO_START,
                                    ReoptimizationPlanStageCommand
                                            .SegmentType.TO_END,
                                    ReoptimizationPlanStageCommand
                                            .SegmentType.TO_END
                            );
                    assertThat(plan.pathSteps())
                            .extracting(
                                    ReoptimizationPlanStageCommand
                                            .PathStepCommand::stepSequence
                            )
                            .containsExactly(0, 1, 0, 1);
                    assertThat(plan.pathSteps().get(1))
                            .satisfies(step -> {
                                assertThat(step.nodeId()).isEqualTo(20L);
                                assertThat(step.arrivalTimeMillis())
                                        .isEqualTo(2_000L);
                                assertThat(step.departureTimeMillis())
                                        .isEqualTo(2_500L);
                            });
                });
    }

    @Test
    void refusesToCreateCommandForNonSucceededResponse() {
        ReoptimizationResponse infeasible = new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                ReoptimizationResponse.Status.INFEASIBLE,
                List.of(),
                List.of(100L),
                null
        );

        assertThatThrownBy(() ->
                ReoptimizationPlanStageCommand.from(
                        request(),
                        infeasible
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private ReoptimizationOptimizationRequest request() {
        return new ReoptimizationOptimizationRequest(
                "replan-1",
                1L,
                7L,
                1_000L,
                1L,
                ReoptimizationReason.OBSTACLE_DETECTED,
                10L,
                List.of(9L, 10L),
                "blocked edge",
                List.of(
                        new ReoptimizationOptimizationRequest.RobotStateInput(
                                10L,
                                10L,
                                90.0,
                                "PAUSED",
                                100L,
                                "IDLE",
                                ReoptimizationOptimizationRequest
                                        .RemainingStage.TO_START
                        )
                ),
                List.of(
                        new ReoptimizationOptimizationRequest.TaskInput(
                                100L,
                                null,
                                20L,
                                30L,
                                "OUTBOUND",
                                "PENDING"
                        )
                )
        );
    }

    private ReoptimizationResponse succeededResponse() {
        TaskPlan taskPlan = new TaskPlan(
                10L,
                100L,
                0,
                TaskPlan.ExecutionStage.FULL,
                List.of(
                        new PathStep(10L, 1_000L, 1_000L),
                        new PathStep(20L, 2_000L, 2_500L)
                ),
                List.of(
                        new PathStep(20L, 2_500L, 3_000L),
                        new PathStep(30L, 4_000L, 4_000L)
                ),
                1_000L,
                4_000L
        );

        return new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                ReoptimizationResponse.Status.SUCCEEDED,
                List.of(taskPlan),
                List.of(),
                "validated plan"
        );
    }
}
