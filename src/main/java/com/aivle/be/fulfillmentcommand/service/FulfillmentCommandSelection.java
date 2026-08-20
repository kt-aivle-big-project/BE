package com.aivle.be.fulfillmentcommand.service;

import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;

import java.util.List;

public record FulfillmentCommandSelection(
        long selectionSeed,
        FulfillmentCommandMode mode,
        int inboundCount,
        int outboundCount,
        CommandExpressionMode commandExpressionMode,
        List<Operation> operations
) {
    public record Operation(
            String operationType,
            String productCode,
            Long warehouseItemId,
            String reason
    ) {}
}
