package com.aivle.be.laro.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LaroHumanReviewResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsSnakeCaseHeldOutcomeFromLaro() {
        String json = """
                {
                  "interaction_id": "HITL-1",
                  "interaction_status": "RESOLVED",
                  "resume_outcome": "HELD",
                  "message": "manual work required",
                  "terminal_status": "held_for_human_action",
                  "workflow_hold": {"reason_code": "TEST_HOLD"},
                  "plan_response": null
                }
                """;

        LaroHumanReviewResponse response = objectMapper.readValue(
                json,
                LaroHumanReviewResponse.class
        );

        assertEquals("HITL-1", response.interactionId());
        assertEquals("RESOLVED", response.interactionStatus());
        assertEquals("HELD", response.resumeOutcome());
        assertEquals("TEST_HOLD", response.workflowHold().get("reason_code"));
        assertNull(response.planResponse());
    }
}
