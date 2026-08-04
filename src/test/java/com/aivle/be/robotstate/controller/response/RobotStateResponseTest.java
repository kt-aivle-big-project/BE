package com.aivle.be.robotstate.controller.response;

import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RobotStateResponseTest {

    @Test
    void exposesAuthoritativeMovementSegmentTiming() {
        RobotState state = new RobotState(
                10006L,
                1L,
                300L,
                "R3_10",
                301L,
                "R2_10",
                0.462,
                "R10006-0018",
                12_992L,
                13_762L,
                13_406L,
                0.5377,
                89,
                RobotStatus.MOVING,
                44L,
                "OUTBOUND",
                RobotStatus.MOVING,
                null,
                null,
                true,
                LocalDateTime.of(2026, 8, 3, 12, 0)
        );

        RobotStateResponse response = RobotStateResponse.from(state);

        assertThat(response.movementStepId()).isEqualTo("R10006-0018");
        assertThat(response.movementStartAtMillis()).isEqualTo(12_992L);
        assertThat(response.movementEndAtMillis()).isEqualTo(13_762L);
        assertThat(response.simulationTimeMillis()).isEqualTo(13_406L);
        assertThat(response.movementProgress()).isEqualTo(0.5377);
    }
}
