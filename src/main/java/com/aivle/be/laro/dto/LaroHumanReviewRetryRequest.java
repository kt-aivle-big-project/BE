package com.aivle.be.laro.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** Confirms that external human work is complete and starts a fresh cycle. */
public record LaroHumanReviewRetryRequest(
        @PositiveOrZero long executionVersion
) {}
