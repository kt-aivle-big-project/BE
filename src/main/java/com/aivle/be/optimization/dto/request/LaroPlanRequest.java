package com.aivle.be.optimization.dto.request;

import java.util.List;
import java.util.Map;

public record LaroPlanRequest(
        String simulationId,
        String optimizationBackend,
        List<EventInput> events,
        String userCommand
) {
    public record EventInput(
            String type,
            String orderId,
            String inboundId,
            String robotId,
            String edgeId,
            String nodeId,
            Map<String, Object> payload
    ) {
    }
}
