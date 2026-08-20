package com.aivle.be.laro.service;

public class StaleSimulationExecutionException extends RuntimeException {

    public StaleSimulationExecutionException(
            Long simulationRunId,
            long expectedExecutionVersion,
            long actualExecutionVersion
    ) {
        super(
                "stale simulation execution: runId=" + simulationRunId
                        + ", expectedVersion=" + expectedExecutionVersion
                        + ", actualVersion=" + actualExecutionVersion
        );
    }
}
