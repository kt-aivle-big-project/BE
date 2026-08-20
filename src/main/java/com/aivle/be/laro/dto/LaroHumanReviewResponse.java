package com.aivle.be.laro.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LaroHumanReviewResponse(
        @JsonProperty("interaction_id") String interactionId,
        @JsonProperty("interaction_status") String interactionStatus,
        @JsonProperty("resume_outcome") String resumeOutcome,
        String message,
        @JsonProperty("terminal_status") String terminalStatus,
        @JsonProperty("workflow_hold") Map<String, Object> workflowHold,
        @JsonProperty("plan_response") LaroPlanResponse planResponse
) {}
