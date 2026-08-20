package com.aivle.be.simulationrun.playback;

import java.util.Objects;

public record RuntimePathStep(
        Long nodeId,
        Long arrivalTimeMillis,
        Long departureTimeMillis
) {

    public RuntimePathStep {
        Objects.requireNonNull(nodeId, "nodeId is required");
        Objects.requireNonNull(
                arrivalTimeMillis,
                "arrivalTimeMillis is required"
        );
        Objects.requireNonNull(
                departureTimeMillis,
                "departureTimeMillis is required"
        );
    }
}
