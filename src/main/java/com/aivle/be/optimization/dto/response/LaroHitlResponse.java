package com.aivle.be.optimization.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record LaroHitlResponse(
        @JsonProperty("interaction_id")
        String interactionId,
        @JsonProperty("interaction_status")
        String interactionStatus,
        @JsonProperty("orchestration_result")
        OrchestrationResult orchestrationResult,
        @JsonProperty("terminal_status")
        String terminalStatus,
        @JsonProperty("terminal_reason_code")
        String terminalReasonCode,
        @JsonProperty("workflow_hold")
        JsonNode workflowHold,
        String message
) {
    public record OrchestrationResult(
            String status,
            @JsonProperty("warehouse_id")
            String warehouseId,
            @JsonProperty("simulation_id")
            String simulationId,
            @JsonProperty("simulation_run_id")
            Long simulationRunId,
            @JsonProperty("request_mode")
            String requestMode,
            @JsonProperty("effective_planning_mode")
            String effectivePlanningMode,
            @JsonProperty("planning_mode_source")
            String planningModeSource,
            @JsonProperty("simulation_plan")
            LaroPlanResponse.SimulationPlan simulationPlan,
            @JsonProperty("frontend_summary")
            JsonNode frontendSummary,
            @JsonProperty("pending_human_interaction")
            JsonNode pendingHumanInteraction,
            @JsonProperty("input_rejection")
            JsonNode inputRejection,
            @JsonProperty("workflow_hold")
            JsonNode workflowHold,
            List<JsonNode> errors
    ) {
        public LaroPlanResponse toPlanResponse() {
            return new LaroPlanResponse(
                    "v1",
                    status,
                    warehouseId,
                    simulationId,
                    requestMode,
                    null,
                    effectivePlanningMode,
                    planningModeSource,
                    false,
                    simulationPlan,
                    null,
                    frontendSummary,
                    pendingHumanInteraction,
                    inputRejection,
                    workflowHold,
                    errors == null ? List.of() : errors
            );
        }
    }

    public LaroPlanResponse resumedPlan() {
        return orchestrationResult == null
                ? null
                : orchestrationResult.toPlanResponse();
    }
}
