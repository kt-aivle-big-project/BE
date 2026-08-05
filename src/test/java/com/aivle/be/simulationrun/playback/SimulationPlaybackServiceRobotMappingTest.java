package com.aivle.be.simulationrun.playback;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationPlaybackServiceRobotMappingTest {

    @Test
    void resolvesOnlyTheExactCanonicalParticipantId() {
        Robot first = robot(10001L);
        Robot second = robot(10002L);

        Robot resolved = SimulationPlaybackService.resolvePlanRobot(
                "R10002",
                List.of(first, second),
                Set.of()
        );

        assertThat(resolved).isSameAs(second);
    }

    @Test
    void rejectsUnknownMalformedAndAlreadyUsedRobotIdsWithoutFallback() {
        Robot first = robot(10001L);
        Robot second = robot(10002L);
        List<Robot> participants = List.of(first, second);

        assertMappingFailure("R99999", participants, Set.of());
        assertMappingFailure("ROBOT10001", participants, Set.of());
        assertMappingFailure("R010001", participants, Set.of());
        assertMappingFailure("R10001", participants, Set.of(10001L));
    }

    @Test
    void canonicalParserUsesTheSpringRobotPrimaryKeyContract() {
        assertThat(SimulationPlaybackService.canonicalRobotDatabaseId("R10001"))
                .isEqualTo(10001L);
        assertThat(SimulationPlaybackService.canonicalRobotDatabaseId("R001"))
                .isNull();
        assertThat(SimulationPlaybackService.canonicalRobotDatabaseId("10001"))
                .isNull();
    }

    private static void assertMappingFailure(
            String robotId,
            List<Robot> participants,
            Set<Long> usedRobotIds
    ) {
        assertThatThrownBy(() -> SimulationPlaybackService.resolvePlanRobot(
                robotId,
                participants,
                usedRobotIds
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LARO_PLAN_MAPPING_FAILED);
    }

    private static Robot robot(long id) {
        Robot robot = new Robot();
        robot.setId(id);
        return robot;
    }
}
