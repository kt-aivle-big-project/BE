package com.aivle.be.robotstate.validation;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robotstate.domain.RobotStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RobotStateTransitionValidatorTest {

    private final RobotStateTransitionValidator validator = new RobotStateTransitionValidator();

    @Test
    void allowsValidTransition() {
        assertThatCode(() -> validator.validate(RobotStatus.IDLE, RobotStatus.ASSIGNED))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsSameStatusUpdate() {
        assertThatCode(() -> validator.validate(RobotStatus.MOVING, RobotStatus.MOVING))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidTransition() {
        assertThatThrownBy(() -> validator.validate(RobotStatus.OFFLINE, RobotStatus.WORKING))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ROBOT_STATE_TRANSITION));
    }
}
