package com.aivle.be.optimization.dto.response;

import java.util.Objects;

/**
 * 시뮬레이션 절대 시각을 포함하는 경로의 한 노드 점유 구간.
 */
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
