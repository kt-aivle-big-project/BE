package com.aivle.be.simulation.controller.request;

public record SimulationAgentInteractionRequest(
        String agentInput,
        String agentOutput,
        Integer tokens,
        Long latency,
        String toolCallId
) {
}