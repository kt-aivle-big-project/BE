package com.aivle.be.optimization.dto.response;

import java.util.Objects;

public record PathStep(
        Long nodeId,
        Long arrivalTimeMillis,
        Long departureTimeMillis
) {

    public PathStep {
        Objects.requireNonNull(nodeId, "nodeId is required");
        Objects.requireNonNull(
                arrivalTimeMillis,
                "arrivalTimeMillis is required"
        );
        Objects.requireNonNull(
                departureTimeMillis,
                "departureTimeMillis is required"
        );

        if (departureTimeMillis < arrivalTimeMillis) {
            throw new IllegalArgumentException(
                    "departureTimeMillis cannot be before arrivalTimeMillis"
            );
        }
    }
}
