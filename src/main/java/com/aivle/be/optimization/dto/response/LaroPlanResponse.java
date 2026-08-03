package com.aivle.be.optimization.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;

public record LaroPlanResponse(
        @JsonProperty("api_version")
        String apiVersion,
        String status,
        @JsonProperty("warehouse_id")
        String warehouseId,
        @JsonProperty("simulation_id")
        String simulationId,
        @JsonProperty("request_mode")
        String requestMode,
        @JsonProperty("final_route")
        String finalRoute,
        @JsonProperty("effective_planning_mode")
        String effectivePlanningMode,
        @JsonProperty("planning_mode_source")
        String planningModeSource,
        @JsonProperty("router_llm_executed")
        Boolean routerLlmExecuted,
        SimulationPlan plan,
        @JsonProperty("evaluation_id")
        String evaluationId,
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
    public boolean isValidated() {
        return "plan_validated".equals(status) && plan != null;
    }

    public record SimulationPlan(
            @JsonProperty("plan_id")
            String planId,
            @JsonProperty("plan_version")
            Integer planVersion,
            @JsonProperty("base_plan_id")
            String basePlanId,
            @JsonProperty("warehouse_id")
            String warehouseId,
            @JsonProperty("simulation_id")
            String simulationId,
            String status,
            @JsonProperty("plan_kind")
            String planKind,
            @JsonProperty("map_version")
            String mapVersion,
            @JsonProperty("plan_start_sim_time_ms")
            Long planStartSimTimeMs,
            @JsonProperty("effective_from_sim_time_ms")
            Long effectiveFromSimTimeMs,
            @JsonProperty("sim_tick_ms")
            Long simTickMs,
            @JsonProperty("makespan_ms")
            Long makespanMs,
            @JsonProperty("absolute_finish_at_ms")
            Long absoluteFinishAtMs,
            List<RobotPlan> robots,
            @JsonProperty("station_reservations")
            List<JsonNode> stationReservations,
            @JsonProperty("logical_operations")
            List<LogicalOperation> logicalOperations,
            @JsonProperty("handover_points")
            List<JsonNode> handoverPoints,
            @JsonProperty("supersedes_plan_id")
            String supersedesPlanId
    ) {
    }

    public record LogicalOperation(
            @JsonProperty("operation_id")
            String operationId,
            @JsonProperty("operation_type")
            String operationType,
            @JsonProperty("assigned_robot_id")
            String assignedRobotId
    ) {
    }

    public record RobotPlan(
            @JsonProperty("robot_id")
            String robotId,
            @JsonProperty("initial_node")
            String initialNode,
            @JsonProperty("available_at_ms")
            Long availableAtMs,
            @JsonProperty("finish_at_ms")
            Long finishAtMs,
            List<PlanStep> steps
    ) {
    }

    public record PlanStep(
            @JsonProperty("step_id")
            String stepId,
            Integer sequence,
            @JsonProperty("step_type")
            String stepType,
            @JsonProperty("start_at_ms")
            Long startAtMs,
            @JsonProperty("end_at_ms")
            Long endAtMs,
            @JsonProperty("node_id")
            String nodeId,
            @JsonProperty("edge_id")
            String edgeId,
            @JsonProperty("from_node")
            String fromNode,
            @JsonProperty("to_node")
            String toNode,
            @JsonProperty("task_id")
            String taskId,
            @JsonProperty("service_kind")
            String serviceKind,
            String reason,
            @JsonProperty("distance_m")
            Double distanceM,
            @JsonProperty("nominal_speed_mps")
            Double nominalSpeedMps,
            @JsonProperty("nominal_travel_time_ms")
            Long nominalTravelTimeMs
    ) {
    }
}
