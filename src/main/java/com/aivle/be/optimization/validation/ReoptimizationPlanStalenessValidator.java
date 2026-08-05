package com.aivle.be.optimization.validation;

import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;

import java.util.Objects;

public final class ReoptimizationPlanStalenessValidator {

    private ReoptimizationPlanStalenessValidator() {
    }

    public static void validate(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response,
            ReplanningSnapshot requestedSnapshot,
            ReplanningSnapshot currentSnapshot
    ) {
        boolean correlationMatches = currentSnapshot != null
                && Objects.equals(
                request.replanId(),
                response.replanId()
        )
                && Objects.equals(
                request.simulationRunId(),
                response.simulationRunId()
        )
                && Objects.equals(
                request.snapshotVersion(),
                response.snapshotVersion()
        )
                && Objects.equals(
                request.replanId(),
                currentSnapshot.replanId()
        )
                && Objects.equals(
                request.simulationRunId(),
                currentSnapshot.simulationRunId()
        )
                && Objects.equals(
                request.snapshotVersion(),
                currentSnapshot.snapshotVersion()
        );
        boolean runtimeStateMatches = correlationMatches
                && Objects.equals(
                requestedSnapshot.simulationRunId(),
                currentSnapshot.simulationRunId()
        )
                && Objects.equals(
                requestedSnapshot.snapshotVersion(),
                currentSnapshot.snapshotVersion()
        )
                && Objects.equals(
                requestedSnapshot.simulationClockMillis(),
                currentSnapshot.simulationClockMillis()
        )
                && Objects.equals(
                requestedSnapshot.robots(),
                currentSnapshot.robots()
        );

        if (!runtimeStateMatches) {
            throw new ReoptimizationPlanValidationException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE,
                    "Replanning context changed while the AI plan was pending"
            );
        }
    }
}
