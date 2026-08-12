package com.aivle.be.laro.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** One-shot natural-language intent injected into the existing command cycle. */
public record LaroUserCommandRequest(
        @NotBlank @Size(max = 4000) String userCommand,
        @PositiveOrZero long executionVersion
) {
}
