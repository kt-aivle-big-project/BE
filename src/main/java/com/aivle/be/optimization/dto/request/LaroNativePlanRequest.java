package com.aivle.be.optimization.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record LaroNativePlanRequest(
        @JsonProperty("warehouse_id")
        String warehouseId,
        @JsonProperty("simulation_run_id")
        Long simulationRunId,
        @JsonProperty("simulation_id")
        String simulationId,
        @JsonProperty("optimization_backend")
        String optimizationBackend,
        List<EventInput> events,
        @JsonProperty("user_command")
        String userCommand
) {
    public record EventInput(
            String type,
            @JsonProperty("order_id")
            String orderId,
            @JsonProperty("inbound_id")
            String inboundId,
            @JsonProperty("robot_id")
            String robotId,
            @JsonProperty("edge_id")
            String edgeId,
            @JsonProperty("node_id")
            String nodeId,
            Map<String, Object> payload
    ) {
    }

    public static LaroNativePlanRequest from(
            String warehouseId,
            LaroPlanRequest request
    ) {
        return from(warehouseId, null, request);
    }

    public static LaroNativePlanRequest from(
            String warehouseId,
            Long simulationRunId,
            LaroPlanRequest request
    ) {
        List<EventInput> events = request.events() == null
                ? List.of()
                : request.events().stream()
                .map(event -> new EventInput(
                        event.type(),
                        event.orderId(),
                        event.inboundId(),
                        event.robotId(),
                        event.edgeId(),
                        event.nodeId(),
                        event.payload() == null ? Map.of() : event.payload()
                ))
                .toList();

        return new LaroNativePlanRequest(
                warehouseId,
                simulationRunId,
                request.simulationId(),
                request.optimizationBackend(),
                events,
                request.userCommand()
        );
    }
}
