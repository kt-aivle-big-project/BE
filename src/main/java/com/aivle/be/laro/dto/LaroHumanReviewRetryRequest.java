package com.aivle.be.laro.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record LaroHumanReviewRetryRequest(
        @PositiveOrZero long executionVersion
) {}
