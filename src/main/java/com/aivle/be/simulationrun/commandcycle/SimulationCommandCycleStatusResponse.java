package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.laro.dto.LaroPlanResponse;

import java.time.LocalDateTime;

public record SimulationCommandCycleStatusResponse(
        Long simulationRunId,
        boolean active,
        CycleState state,
        long simulatedTimeMs,
        long cycleMinute,
        long nextGenerationAtMs,
        String planningMode,
        CommandExpressionMode commandExpressionMode,
        CommandPolicyProfile policyProfile,
        FulfillmentCommandGenerateResponse generated,
        LaroPlanResponse planResponse,
        String error,
        LocalDateTime updatedAt
) {
    public enum CycleState {
        IDLE,
        CHECKING,
        GENERATING,
        PLANNING,
        REPLANNING,
        COMPLETE,
        ERROR,
        STOPPED
    }
}
