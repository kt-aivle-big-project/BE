package com.aivle.be.fulfillmentcommand.controller.response;

import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

public record FulfillmentCommandGenerateResponse(
        LaroPlanRequest planRequest,
        FrontView frontView
) {
    public record FrontView(
            String requestId,
            Long simulationRunId,
            Long warehouseId,
            String warehouseName,
            FulfillmentCommandMode requestedMode,
            FulfillmentCommandMode mode,
            CommandExpressionMode requestedCommandExpressionMode,
            CommandExpressionMode commandExpressionMode,
            CommandPolicyProfile policyProfile,
            LocalDateTime generatedAt,
            Summary summary,
            List<Command> commands,
            List<String> warnings
    ) {}

    public record Summary(
            int requestedInboundCommands,
            int generatedInboundCommands,
            int requestedOutboundCommands,
            int generatedOutboundCommands,
            int totalStorageLocations,
            int totalStorageSlots,
            int occupiedStorageSlots,
            int emptyStorageSlots,
            int availableOutboundBoxes,
            int excludedReservedBoxes,
            long totalInventoryUnits,
            long generatedInboundUnits,
            long generatedOutboundUnits
    ) {}

    public record Command(
            int sequence,
            String operationId,
            LaroPlanRequest.OperationType operationType,
            Long productId,
            String productCode,
            String productName,
            String category,
            int quantity,
            String quantityUnit,
            int unitsPerBox,
            int boxCount,
            String priority,
            long releaseAtMs,
            Location source,
            Location destination,
            long warehouseProductUnitsBefore,
            long warehouseProductUnitsAfter,
            String reason
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(
            String kind,
            String label,
            Long warehouseItemId,
            Long storageLocationId,
            Integer rackLevel,
            Long nodeId,
            String nodeCode,
            String facilityCode
    ) {}
}
