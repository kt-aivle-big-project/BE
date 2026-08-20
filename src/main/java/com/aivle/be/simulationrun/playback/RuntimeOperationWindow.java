package com.aivle.be.simulationrun.playback;

import java.util.Objects;

public record RuntimeOperationWindow(
        Long nodeId,
        Long startTimeMillis,
        Long endTimeMillis
) {
    public RuntimeOperationWindow {
        Objects.requireNonNull(nodeId, "nodeId is required");
        Objects.requireNonNull(startTimeMillis, "startTimeMillis is required");
        Objects.requireNonNull(endTimeMillis, "endTimeMillis is required");
        if (startTimeMillis >= endTimeMillis) {
            throw new IllegalArgumentException("operation window is invalid");
        }
    }
}
