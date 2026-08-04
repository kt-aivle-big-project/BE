package com.aivle.be.laro.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** LARO FastAPI 응답의 안정적인 공개 영역을 표현하는 DTO. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LaroPlanResponse(
        @JsonProperty("api_version") String apiVersion,
        @JsonProperty("simulation_run_id") Long simulationRunId,
        @JsonProperty("warehouse_id") String warehouseId,
        @JsonProperty("warehouse_numeric_id") Long warehouseNumericId,
        @JsonProperty("request_id") String requestId,
        Result result,
        @JsonProperty("trace_url") String traceUrl,
        @JsonProperty("debug_url") String debugUrl
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            String status,
            @JsonProperty("warehouse_id") String warehouseId,
            @JsonProperty("simulation_id") String simulationId,
            @JsonProperty("request_mode") String requestMode,
            @JsonProperty("final_route") String finalRoute,
            @JsonProperty("effective_planning_mode") String effectivePlanningMode,
            @JsonProperty("planning_mode_source") String planningModeSource,
            @JsonProperty("router_llm_executed") Boolean routerLlmExecuted,
            SimulationPlan plan,
            @JsonProperty("frontend_summary") Map<String, Object> frontendSummary,
            @JsonProperty("pending_human_interaction") Map<String, Object> pendingHumanInteraction,
            @JsonProperty("input_rejection") Map<String, Object> inputRejection,
            @JsonProperty("workflow_hold") Map<String, Object> workflowHold,
            List<WorkflowError> errors
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulationPlan(
            @JsonProperty("plan_id") String planId,
            @JsonProperty("plan_version") Integer planVersion,
            @JsonProperty("base_plan_id") String basePlanId,
            @JsonProperty("warehouse_id") String warehouseId,
            @JsonProperty("simulation_id") String simulationId,
            String status,
            @JsonProperty("plan_kind") String planKind,
            @JsonProperty("map_version") String mapVersion,
            @JsonProperty("sim_tick_ms") Integer simTickMs,
            @JsonProperty("plan_start_sim_time_ms") Long planStartSimTimeMs,
            @JsonProperty("effective_from_sim_time_ms") Long effectiveFromSimTimeMs,
            @JsonProperty("makespan_ms") Long makespanMs,
            @JsonProperty("absolute_finish_at_ms") Long absoluteFinishAtMs,
            List<RobotPlan> robots,
            @JsonProperty("station_reservations") List<Map<String, Object>> stationReservations,
            @JsonProperty("logical_operations") List<LogicalOperation> logicalOperations,
            @JsonProperty("handover_points") List<HandoverPoint> handoverPoints,
            @JsonProperty("supersedes_plan_id") String supersedesPlanId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HandoverPoint(
            @JsonProperty("robot_id") String robotId,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("handover_at_ms") Long handoverAtMs,
            String reason,
            @JsonProperty("handover_policy") String handoverPolicy,
            @JsonProperty("current_step_id") String currentStepId,
            @JsonProperty("locked_task_ids") List<String> lockedTaskIds,
            @JsonProperty("carrying_load") Boolean carryingLoad
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RobotPlan(
            @JsonProperty("robot_id") String robotId,
            @JsonProperty("initial_node") String initialNode,
            @JsonProperty("available_at_ms") Long availableAtMs,
            @JsonProperty("finish_at_ms") Long finishAtMs,
            List<PlanStep> steps
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanStep(
            @JsonProperty("step_id") String stepId,
            Integer sequence,
            @JsonProperty("step_type") String stepType,
            @JsonProperty("start_at_ms") Long startAtMs,
            @JsonProperty("end_at_ms") Long endAtMs,
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("edge_id") String edgeId,
            @JsonProperty("from_node") String fromNode,
            @JsonProperty("to_node") String toNode,
            @JsonProperty("task_id") String taskId,
            @JsonProperty("service_kind") String serviceKind,
            String reason,
            @JsonProperty("distance_m") Double distanceM
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LogicalOperation(
            @JsonProperty("operation_id") String operationId,
            @JsonProperty("operation_type") String operationType,
            @JsonProperty("item_id") String itemId,
            Integer quantity,
            @JsonProperty("rack_id") String rackId,
            @JsonProperty("rack_level") Integer rackLevel,
            @JsonProperty("logical_destination_id") String logicalDestinationId,
            @JsonProperty("source_port_id") String sourcePortId,
            @JsonProperty("handling_unit_id") String inventoryUnitCompatibilityId,
            @JsonProperty("assigned_robot_id") String assignedRobotId,
            @JsonProperty("task_ids") List<String> taskIds
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkflowError(
            String stage,
            String code,
            String message,
            Boolean retryable
    ) {}
}
