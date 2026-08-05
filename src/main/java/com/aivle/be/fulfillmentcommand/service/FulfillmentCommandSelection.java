package com.aivle.be.fulfillmentcommand.service;

import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;

import java.util.List;

/** Internal Spring-to-AI contract after Java has selected a feasible BOX batch. */
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
