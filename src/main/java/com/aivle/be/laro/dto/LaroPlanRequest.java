package com.aivle.be.laro.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LaroPlanRequest(
        @NotNull @Valid StructuredInput structuredInput,
        @Size(max = 4000) String userCommand,
        String optimizationBackend,
        @Valid RuntimeSnapshot runtimeSnapshot
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructuredInput(
            @Size(max = 128) String requestId,
            @NotEmpty List<@Valid StructuredOperation> operations,
            Map<String, Object> constraints,
            @Valid RoutingContext routingContext
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoutingContext(
            @PositiveOrZero Integer newOperationCount,
            @PositiveOrZero Integer unfinishedOperationCount,
            @PositiveOrZero Integer eligibleRobotCount,
            @PositiveOrZero Integer totalRobotCount,
            @PositiveOrZero Integer lowBatteryRobotCount,
            @PositiveOrZero Integer activeRobotCount,
            String source
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructuredOperation(
            @NotBlank @Size(max = 128) String operationId,
            @NotNull OperationType operationType,
            @Positive Long taskId,
            @Positive Long itemId,
            @Size(max = 64) String productCode,
            @Positive Integer quantity,
            String priority,

            @Positive Long sourceWarehouseItemId,
            @Positive Long sourceStorageLocationId,
            @Positive Long sourceNodeId,
            @Size(max = 100) String sourceNodeCode,
            @Size(max = 100) String sourceFacilityCode,

            @Positive Long destinationStorageLocationId,
            @Positive Long destinationNodeId,
            @Size(max = 100) String destinationNodeCode,
            @Size(max = 100) String destinationFacilityCode,
            @Positive Integer targetRackLevel,

            @PositiveOrZero Long releaseAtMs,
            @PositiveOrZero Long pickupServiceTimeMs,
            @PositiveOrZero Long dropServiceTimeMs,
            @Size(max = 2000) String attributes
    ) {}

    public enum OperationType {
        OUTBOUND,
        INBOUND,
        TRANSFER,
        CHARGE,
        PARK
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuntimeSnapshot(
            String mode,
            @PositiveOrZero Long capturedAtSimTimeMs,
            List<Map<String, Object>> robotStates,
            List<Map<String, Object>> preservedEdgeReservations,
            List<Map<String, Object>> preservedNodeReservations,
            List<Map<String, Object>> preservedStationReservations
    ) {}
}
