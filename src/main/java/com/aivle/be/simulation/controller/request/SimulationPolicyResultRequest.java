package com.aivle.be.simulation.controller.request;

public record SimulationPolicyResultRequest(
        String ruleCode,
        String policyResult
) {
}