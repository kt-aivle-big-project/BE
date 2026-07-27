package com.aivle.be.robotstate.controller.request;

import com.aivle.be.robotstate.domain.RobotStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RobotStateUpdateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBatteryBetweenZeroAndOneHundred() {
        RobotStateUpdateRequest request = new RobotStateUpdateRequest(
                125L, 67, RobotStatus.MOVING, null, LocalDateTime.now()
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBatteryOverOneHundred() {
        RobotStateUpdateRequest request = new RobotStateUpdateRequest(
                125L, 101, RobotStatus.IDLE, null, LocalDateTime.now()
        );

        assertThat(validator.validate(request)).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("batteryLevel"));
    }
}
