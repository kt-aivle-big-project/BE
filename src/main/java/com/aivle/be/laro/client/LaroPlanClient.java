package com.aivle.be.laro.client;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.dto.LaroHumanReviewRequest;
import com.aivle.be.laro.dto.LaroHumanReviewResponse;
import com.aivle.be.laro.dto.LaroLowBatteryContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LaroPlanClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LaroPlanClient(
            @Value("${fastapi.base-url}") String fastApiBaseUrl,
            ObjectMapper objectMapper
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(fastApiBaseUrl)
                .build();
        this.objectMapper = objectMapper;
    }

    public LaroPreflightResponse preflight(Long simulationRunId) {
        return restClient.get()
                .uri("/api/v1/simulation-runs/{id}/missions/plan/preflight", simulationRunId)
                .retrieve()
                .body(LaroPreflightResponse.class);
    }

    public LaroPlanResponse plan(Long simulationRunId, LaroPlanRequest request) {
        return restClient.post()
                .uri("/api/v1/simulation-runs/{id}/missions/plan", simulationRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialize(toAiRequest(request)))
                .retrieve()
                .body(LaroPlanResponse.class);
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            String activePlanId,
            Integer activePlanVersion,
            long replanAtSimTimeMs,
            LaroPlanRequest request
    ) {
        return replan(
                simulationRunId,
                activePlanId,
                activePlanVersion,
                replanAtSimTimeMs,
                request,
                "NEW_ORDER"
        );
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            String activePlanId,
            Integer activePlanVersion,
            long replanAtSimTimeMs,
            LaroPlanRequest request,
            String reason
    ) {
        return replan(
                simulationRunId,
                activePlanId,
                activePlanVersion,
                replanAtSimTimeMs,
                request,
                reason,
                null
        );
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            String activePlanId,
            Integer activePlanVersion,
            long replanAtSimTimeMs,
            LaroPlanRequest request,
            String reason,
            LaroLowBatteryContext lowBatteryContext
    ) {
        Map<String, Object> body = toAiRequest(request);
        body.put("active_plan_id", activePlanId);
        put(body, "active_plan_version", activePlanVersion);
        body.put("replan_at_sim_time_ms", replanAtSimTimeMs);
        body.put(
                "reason",
                reason == null || reason.isBlank()
                        ? "NEW_ORDER"
                        : reason.trim().toUpperCase()
        );
        body.put("activation_policy", "ALL_ROBOTS_READY");
        if (lowBatteryContext != null) {
            body.put(
                    "low_battery_context",
                    toAiLowBatteryContext(lowBatteryContext)
            );
        }

        LaroPlanResponse response = restClient.post()
                .uri("/api/v1/simulation-runs/{id}/missions/replan", simulationRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialize(body))
                .retrieve()
                .body(LaroPlanResponse.class);
        if (response == null) {
            throw new IllegalStateException("LARO replan returned an empty response");
        }
        return response;
    }

    public LaroHumanReviewResponse respondToHumanReview(
            Long simulationRunId,
            String interactionId,
            LaroHumanReviewRequest request,
            String actorId
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", request.action().trim().toUpperCase());
        put(body, "selected_option_id", request.selectedOptionId());
        body.put("selected_entity_ids", request.effectiveSelectedEntityIds());
        put(body, "resolution_value", request.resolutionValue());
        body.put("actor_id", actorId);
        put(body, "comment", request.comment());

        LaroHumanReviewResponse response = restClient.post()
                .uri(
                        "/api/v1/simulation-runs/{runId}/hitl/{interactionId}/respond",
                        simulationRunId,
                        interactionId
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(serialize(body))
                .retrieve()
                .body(LaroHumanReviewResponse.class);
        if (response == null) {
            throw new IllegalStateException("LARO human review returned an empty response");
        }
        return response;
    }

    private String serialize(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize LARO plan request", exception);
        }
    }

    private static Map<String, Object> toAiRequest(LaroPlanRequest request) {
        LaroPlanRequest.StructuredInput source = request.structuredInput();
        List<Map<String, Object>> operations = source.operations().stream()
                .map(LaroPlanClient::toAiOperation)
                .toList();
        Map<String, Object> structuredInput = new LinkedHashMap<>();
        put(structuredInput, "request_id", source.requestId());
        structuredInput.put("operations", operations);
        put(structuredInput, "constraints", source.constraints());
        if (source.routingContext() != null) {
            Map<String, Object> routingContext = new LinkedHashMap<>();
            put(routingContext, "new_operation_count", source.routingContext().newOperationCount());
            put(routingContext, "unfinished_operation_count", source.routingContext().unfinishedOperationCount());
            put(routingContext, "eligible_robot_count", source.routingContext().eligibleRobotCount());
            put(routingContext, "total_robot_count", source.routingContext().totalRobotCount());
            put(routingContext, "low_battery_robot_count", source.routingContext().lowBatteryRobotCount());
            put(routingContext, "active_robot_count", source.routingContext().activeRobotCount());
            put(routingContext, "source", source.routingContext().source());
            structuredInput.put("routing_context", routingContext);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("structured_input", structuredInput);
        put(body, "user_command", request.userCommand());
        put(body, "optimization_backend", request.optimizationBackend());
        if (request.runtimeSnapshot() != null) {
            Map<String, Object> runtime = new LinkedHashMap<>();
            put(runtime, "mode", request.runtimeSnapshot().mode());
            put(runtime, "captured_at_sim_time_ms", request.runtimeSnapshot().capturedAtSimTimeMs());
            put(runtime, "robot_states", request.runtimeSnapshot().robotStates());
            put(runtime, "preserved_edge_reservations", request.runtimeSnapshot().preservedEdgeReservations());
            put(runtime, "preserved_node_reservations", request.runtimeSnapshot().preservedNodeReservations());
            put(runtime, "preserved_station_reservations", request.runtimeSnapshot().preservedStationReservations());
            body.put("runtime_snapshot", runtime);
        }
        return body;
    }

    private static Map<String, Object> toAiOperation(
            LaroPlanRequest.StructuredOperation value
    ) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operation_id", value.operationId());
        operation.put("operation_type", value.operationType().name());
        put(operation, "task_id", value.taskId());
        put(operation, "item_id", value.itemId());
        put(operation, "product_code", value.productCode());
        put(operation, "quantity", value.quantity());
        put(operation, "priority", value.priority());
        put(operation, "source_warehouse_item_id", value.sourceWarehouseItemId());
        put(operation, "source_storage_location_id", value.sourceStorageLocationId());
        put(operation, "source_node_id", value.sourceNodeId());
        put(operation, "source_node_code", value.sourceNodeCode());
        put(operation, "source_facility_code", value.sourceFacilityCode());
        put(operation, "destination_storage_location_id", value.destinationStorageLocationId());
        put(operation, "destination_node_id", value.destinationNodeId());
        put(operation, "destination_node_code", value.destinationNodeCode());
        put(operation, "destination_facility_code", value.destinationFacilityCode());
        put(operation, "target_rack_level", value.targetRackLevel());
        put(operation, "release_at_ms", value.releaseAtMs());
        put(operation, "pickup_service_time_ms", value.pickupServiceTimeMs());
        put(operation, "drop_service_time_ms", value.dropServiceTimeMs());
        put(operation, "attributes", value.attributes());
        return operation;
    }

    private static Map<String, Object> toAiLowBatteryContext(
            LaroLowBatteryContext value
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("status", "LOW_BATTERY");
        context.put("robot_id", "R" + value.robotId());
        put(context, "robot_numeric_id", value.robotId());
        context.put("battery_pct", value.batteryLevel());
        context.put("charging_threshold_pct", value.chargingThreshold());
        put(context, "current_node", value.currentNodeCode());
        put(context, "current_node_numeric_id", value.currentNodeId());
        put(context, "current_task_id", value.currentTaskId());
        context.put("carrying_load", value.carryingLoad());
        context.put("stopped_at_sim_time_ms", value.stoppedAtSimTimeMs());
        return context;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
