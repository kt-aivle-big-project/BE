package com.aivle.be.optimization.dto.response;

import java.util.Objects;

public record TaskOperationWindow(
        Long nodeId,
        Long startTimeMillis,
        Long endTimeMillis
) {

    public TaskOperationWindow {
        Objects.requireNonNull(nodeId, "nodeId is required");
        Objects.requireNonNull(startTimeMillis, "startTimeMillis is required");
        Objects.requireNonNull(endTimeMillis, "endTimeMillis is required");
        if (startTimeMillis >= endTimeMillis) {
            throw new IllegalArgumentException(
                    "operation window start must be before end"
            );
        }
    }
}
