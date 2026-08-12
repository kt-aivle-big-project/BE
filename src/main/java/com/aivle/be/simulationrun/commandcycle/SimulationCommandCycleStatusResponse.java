package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroHumanReviewResponse;

import java.time.Instant;
import java.util.Map;

public record SimulationCommandCycleStatusResponse(
        Long simulationRunId,
        long executionVersion,
        boolean active,
        CycleState state,
        long simulatedTimeMs,
        long cycleMinute,
        long nextGenerationAtMs,
        String planningMode,
        CommandExpressionMode commandExpressionMode,
        CommandPolicyProfile policyProfile,
        int generationIntervalSeconds,
        Double averageTasksPerRobot,
        String userCommand,
        FulfillmentCommandGenerateResponse generated,
        LaroPlanResponse planResponse,
        Map<String, Object> pendingHumanInteraction,
        LaroHumanReviewResponse humanReviewResponse,
        String error,
        Instant updatedAt
) {
    public enum CycleState {
        IDLE,
        CHECKING,
        GENERATING,
        PLANNING,
        REPLANNING,
        REVIEW_REQUIRED,
        REVIEW_PROCESSING,
        HELD,
        CANCELLED,
        COMPLETE,
        ERROR,
        STOPPED
    }
}
