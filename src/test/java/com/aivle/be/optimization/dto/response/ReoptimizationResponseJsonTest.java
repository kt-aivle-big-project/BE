package com.aivle.be.optimization.dto.response;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesSuccessfulTaskAwarePlanFixture() throws Exception {
        ReoptimizationResponse response = readFixture(
                "success-task-plan.json"
        );

        assertThat(response.requestId())
                .isEqualTo("mock-success-request");
        assertThat(response.replanId())
                .isEqualTo("fixture-replan-id");
        assertThat(response.simulationRunId()).isEqualTo(1L);
        assertThat(response.snapshotVersion()).isEqualTo(1L);
        assertThat(response.status())
                .isEqualTo(ReoptimizationResponse.Status.SUCCEEDED);
        assertThat(response.taskPlans()).singleElement()
                .satisfies(plan -> {
                    assertThat(plan.robotId()).isEqualTo(10L);
                    assertThat(plan.taskId()).isEqualTo(100L);
                    assertThat(plan.sequence()).isZero();
                    assertThat(plan.executionStage())
                            .isEqualTo(TaskPlan.ExecutionStage.FULL);
                    assertThat(plan.pathToStart())
                            .extracting(PathStep::nodeId)
                            .containsExactly(10L, 20L);
                    assertThat(plan.pathToEnd())
                            .extracting(PathStep::nodeId)
                            .containsExactly(20L, 30L);
                    assertThat(plan.pathToStart().get(0))
                            .isEqualTo(new PathStep(10L, 1_000L, 1_000L));
                    assertThat(plan.estimatedStartTimeMillis())
                            .isEqualTo(1_000L);
                    assertThat(plan.estimatedCompletionTimeMillis())
                            .isEqualTo(18_000L);
                    assertThat(plan.pickingWindow()).isEqualTo(
                            new TaskOperationWindow(20L, 4_000L, 9_000L)
                    );
                    assertThat(plan.droppingWindow()).isEqualTo(
                            new TaskOperationWindow(30L, 13_000L, 18_000L)
                    );
                });
        assertThat(response.unassignedTaskIds()).isEmpty();
    }

    @Test
    void deserializesInfeasibleFixtureWithEmptyTaskPlans()
            throws Exception {
        ReoptimizationResponse response = readFixture(
                "infeasible-plan.json"
        );

        assertThat(response.status())
                .isEqualTo(ReoptimizationResponse.Status.INFEASIBLE);
        assertThat(response.taskPlans()).isEmpty();
        assertThat(response.unassignedTaskIds())
                .containsExactly(100L);
        assertThat(response.message()).isEqualTo("no feasible plan");
    }

    @Test
    void invalidSemanticFixturesStillMatchTheJsonContract()
            throws Exception {
        ReoptimizationResponse duplicateTask = readFixture(
                "invalid-duplicate-task.json"
        );
        ReoptimizationResponse blockedEdge = readFixture(
                "invalid-blocked-edge.json"
        );

        assertThat(duplicateTask.taskPlans()).hasSize(2);
        assertThat(blockedEdge.taskPlans()).singleElement()
                .satisfies(plan -> assertThat(plan.pathToStart())
                        .extracting(PathStep::nodeId)
                        .containsExactly(10L, 20L));
    }

    @Test
    void deserializesExplicitWaitAtNode() throws Exception {
        ReoptimizationResponse response = readFixture(
                "success-path-with-wait.json"
        );

        PathStep waitingStep = response.taskPlans().get(0)
                .pathToStart().get(0);

        assertThat(waitingStep.nodeId()).isEqualTo(10L);
        assertThat(waitingStep.arrivalTimeMillis()).isEqualTo(1_000L);
        assertThat(waitingStep.departureTimeMillis()).isEqualTo(3_000L);
        assertThat(waitingStep.departureTimeMillis())
                .isGreaterThan(waitingStep.arrivalTimeMillis());
    }

    @Test
    void rejectsNonMonotonicPathTime() {
        assertThatThrownBy(() -> readFixture(
                "invalid-non-monotonic-time.json"
        )).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDepartureBeforeArrival() {
        assertThatThrownBy(() -> readFixture(
                "invalid-departure-before-arrival.json"
        )).hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void phase22PlanFixturesMatchTheTaskPlanJsonContract()
            throws Exception {
        List<String> fixtures = List.of(
                "invalid-missing-task.json",
                "invalid-unknown-task.json",
                "invalid-unknown-robot.json",
                "invalid-failed-robot-assignment.json",
                "invalid-sequence-start.json",
                "invalid-sequence-gap.json",
                "invalid-sequence-duplicate.json",
                "invalid-path-node.json",
                "invalid-path-disconnected-edge.json",
                "invalid-node-conflict.json",
                "invalid-head-on-edge-conflict.json",
                "invalid-same-direction-edge-conflict.json",
                "success-multiple-robots.json",
                "success-multiple-tasks-one-robot.json"
        );

        for (String fixture : fixtures) {
            assertThat(readFixture(fixture)).isNotNull();
        }
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
}
