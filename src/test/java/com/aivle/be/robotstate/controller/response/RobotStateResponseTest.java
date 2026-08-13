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
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 8, 3, 12, 0)
        );

        RobotStateResponse response = RobotStateResponse.from(state);

        assertThat(response.movementStepId()).isEqualTo("R10006-0018");
        assertThat(response.movementStartAtMillis()).isEqualTo(12_992L);
        assertThat(response.movementEndAtMillis()).isEqualTo(13_762L);
        assertThat(response.simulationTimeMillis()).isEqualTo(13_406L);
        assertThat(response.movementProgress()).isEqualTo(0.5377);
    }

    @Test
    void exposesPlannedWaitMetadataForTheEventList() {
        RobotState state = new RobotState(
                10006L,
                1L,
                300L,
                "R3_10",
                null,
                null,
                null,
                null,
                null,
                null,
                15_000L,
                null,
                89,
                RobotStatus.ASSIGNED,
                44L,
                "OUTBOUND",
                RobotStatus.WAITING,
                null,
                null,
                true,
                "통행 예약 순서를 기다리는 중",
                "R2_10",
                10007L,
                12_000L,
                18_000L,
                LocalDateTime.of(2026, 8, 3, 12, 0)
        );

        RobotStateResponse response = RobotStateResponse.from(state);

        assertThat(response.activity()).isEqualTo(RobotStatus.WAITING);
        assertThat(response.waitingReason()).isEqualTo("통행 예약 순서를 기다리는 중");
        assertThat(response.waitingNodeCode()).isEqualTo("R2_10");
        assertThat(response.blockingRobotId()).isEqualTo(10007L);
        assertThat(response.waitStartedAtMillis()).isEqualTo(12_000L);
        assertThat(response.estimatedResumeAtMillis()).isEqualTo(18_000L);
    }
}
