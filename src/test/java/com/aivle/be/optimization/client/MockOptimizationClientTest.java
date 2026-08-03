package com.aivle.be.optimization.client;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockOptimizationClientTest {

    @Test
    void mockAiProfileSelectsFixtureClientWithoutHttpClient() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("mock-ai");
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource(
                            "mock-ai-test",
                            Map.of(
                                    "mock-ai.reoptimization-fixture",
                                    "classpath:fixtures/reoptimization/"
                                            + "success-task-plan.json",
                                    "fastapi.base-url",
                                    "http://should-not-be-called.invalid"
                            )
                    )
            );
            context.getBeanFactory().registerSingleton(
                    "objectMapper",
                    new ObjectMapper()
            );
            context.register(
                    HttpOptimizationClient.class,
                    MockOptimizationClient.class
            );
            context.refresh();

            assertThat(context.getBeansOfType(HttpOptimizationClient.class))
                    .isEmpty();

            OptimizationClient client = context.getBean(
                    OptimizationClient.class
            );
            assertThat(client).isInstanceOf(MockOptimizationClient.class);

            ReoptimizationResponse response = client.reoptimize(request());

            assertThat(response.status())
                    .isEqualTo(ReoptimizationResponse.Status.SUCCEEDED);
            assertThat(response.replanId()).isEqualTo("replan-1");
            assertThat(response.simulationRunId()).isEqualTo(1L);
            assertThat(response.snapshotVersion()).isEqualTo(7L);
            assertThat(response.taskPlans()).hasSize(1);
        }
    }

    @Test
    void missingFixtureFailsExplicitly() {
        MockOptimizationClient client = new MockOptimizationClient(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                ""
        );

        assertThatThrownBy(() -> client.reoptimize(request()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.MOCK_AI_PLAN_NOT_CONFIGURED
                                )
                );
    }

    @Test
    void duplicateTaskFixtureIsRejected() {
        MockOptimizationClient client = new MockOptimizationClient(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "classpath:fixtures/reoptimization/"
                        + "invalid-duplicate-task.json"
        );

        assertThatThrownBy(() -> client.reoptimize(request()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.MOCK_AI_PLAN_INVALID)
                );
    }

    @Test
    void toEndReassignmentFixtureIsRejected() {
        MockOptimizationClient client = new MockOptimizationClient(
                new ObjectMapper(),
                new DefaultResourceLoader(),
                "classpath:fixtures/reoptimization/"
                        + "invalid-to-end-reassignment.json"
        );

        assertThatThrownBy(() -> client.reoptimize(request()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.MOCK_AI_PLAN_INVALID)
                );
    }

    private ReoptimizationOptimizationRequest request() {
        return new ReoptimizationOptimizationRequest(
                "replan-1",
                1L,
                7L,
                1_000L,
                1L,
                ReoptimizationReason.MANUAL_REQUEST,
                null,
                List.of(),
                "mock client test",
                List.of(
                        new ReoptimizationOptimizationRequest.RobotStateInput(
                                10L,
                                10L,
                                80.0,
                                "PAUSED",
                                100L,
                                "IDLE",
                                ReoptimizationOptimizationRequest
                                        .RemainingStage.IDLE
                        ),
                        new ReoptimizationOptimizationRequest.RobotStateInput(
                                11L,
                                11L,
                                90.0,
                                "PAUSED",
                                null,
                                "IDLE",
                                ReoptimizationOptimizationRequest
                                        .RemainingStage.IDLE
                        )
                ),
                List.of(
                        new ReoptimizationOptimizationRequest.TaskInput(
                                100L,
                                10L,
                                20L,
                                30L,
                                "OUTBOUND",
                                "ASSIGNED"
                        )
                )
        );
    }
}
