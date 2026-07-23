package com.aivle.be.robotspec.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RobotSpecRequest(
        @NotBlank String robotCode,
        @NotBlank String taskCode,
        @NotNull @PositiveOrZero Double baseBatteryRate,
        @NotNull @PositiveOrZero Double workBatteryRate,
        @NotNull
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        Double failureRate
) {
}
