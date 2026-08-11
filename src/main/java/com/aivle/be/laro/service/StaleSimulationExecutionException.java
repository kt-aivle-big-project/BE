package com.aivle.be.laro.service;

/**
 * AI 요청을 시작한 뒤 같은 simulationRunId가 초기화되어 실행 세대가 바뀐 경우.
 */
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
