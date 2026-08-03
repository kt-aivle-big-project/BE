package com.aivle.be.optimization.validation;

import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;
import com.aivle.be.simulationrun.playback.RobotRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReoptimizationPlanStalenessValidatorTest {

    @Test
    void acceptsMatchingRequestResponseAndCurrentContext() {
        assertThatCode(() -> ReoptimizationPlanStalenessValidator.validate(
                request(),
                response(),
                requestedSnapshot(),
                currentSnapshot(7L, 80.0)
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsChangedVersionOrRuntimeState() {
        assertStale(currentSnapshot(8L, 80.0));
        assertStale(currentSnapshot(7L, 70.0));
    }

    @Test
    void rejectsResponseCorrelationMismatch() {
        ReoptimizationResponse mismatchedResponse =
                new ReoptimizationResponse(
                        "request-1",
                        "replan-1",
                        1L,
                        8L,
                        ReoptimizationResponse.Status.INFEASIBLE,
                        List.of(),
                        List.of(),
                        null
                );

        assertThatThrownBy(() ->
                ReoptimizationPlanStalenessValidator.validate(
                        request(),
                        mismatchedResponse,
                        requestedSnapshot(),
                        currentSnapshot(7L, 80.0)
                )
        ).isInstanceOfSatisfying(
                ReoptimizationPlanValidationException.class,
                exception -> org.assertj.core.api.Assertions
                        .assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REOPTIMIZATION_PLAN_STALE)
        );
    }

    private void assertStale(ReplanningSnapshot currentSnapshot) {
        assertThatThrownBy(() ->
                ReoptimizationPlanStalenessValidator.validate(
                        request(),
                        response(),
                        requestedSnapshot(),
                        currentSnapshot
                )
        ).isInstanceOfSatisfying(
                ReoptimizationPlanValidationException.class,
                exception -> org.assertj.core.api.Assertions
                        .assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REOPTIMIZATION_PLAN_STALE)
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
                "stale test",
                List.of(),
                List.of()
        );
    }

    private ReoptimizationResponse response() {
        return new ReoptimizationResponse(
                "request-1",
                "replan-1",
                1L,
                7L,
                ReoptimizationResponse.Status.INFEASIBLE,
                List.of(),
                List.of(),
                null
        );
    }

    private ReplanningSnapshot requestedSnapshot() {
        return new ReplanningSnapshot(
                null,
                1L,
                7L,
                1_000L,
                List.of(robot(80.0))
        );
    }

    private ReplanningSnapshot currentSnapshot(
            Long snapshotVersion,
            Double batteryLevel
    ) {
        return new ReplanningSnapshot(
                "replan-1",
                1L,
                snapshotVersion,
                1_000L,
                List.of(robot(batteryLevel))
        );
    }

    private ReplanningSnapshot.RobotSnapshot robot(Double batteryLevel) {
        return new ReplanningSnapshot.RobotSnapshot(
                10L,
                10L,
                batteryLevel,
                RobotStatus.PAUSED,
                100L,
                RobotRuntime.Phase.IDLE,
                0L
        );
    }
}
