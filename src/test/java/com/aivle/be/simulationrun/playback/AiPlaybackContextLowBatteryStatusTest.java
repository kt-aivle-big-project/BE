package com.aivle.be.simulationrun.playback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AiPlaybackContextLowBatteryStatusTest {

    @Test
    void directChargeRecoveryRemainsReturningDuringMapfWait() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                101L,
                List.of(
                        waitStep(1, 10L),
                        moveStep(2, 10L, 20L),
                        chargeStep(3, 20L)
                ),
                10L,
                20
        );

        assertThat(robot.isReturningToCharge(20)).isTrue();
        assertThat(robot.needsLowBatteryReplan(20)).isFalse();

        robot.advanceStep();
        assertThat(robot.isReturningToCharge(20)).isTrue();
        assertThat(robot.needsLowBatteryReplan(20)).isFalse();

        robot.advanceStep();
        assertThat(robot.isReturningToCharge(20)).isFalse();
        assertThat(robot.needsLowBatteryReplan(20)).isFalse();
    }

    @Test
    void businessServiceBeforeChargeIsNotMisreportedAsDirectReturn() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                101L,
                List.of(
                        serviceStep(1, 10L, "PICKUP"),
                        moveStep(2, 10L, 20L),
                        chargeStep(3, 20L)
                ),
                10L,
                20
        );

        assertThat(robot.isReturningToCharge(20)).isFalse();
        assertThat(robot.needsLowBatteryReplan(20)).isTrue();
    }

    @Test
    void lowBatteryHoldPrecedesReturnStatusUntilReplacementPlanArrives() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                101L,
                List.of(moveStep(1, 10L, 20L), chargeStep(2, 20L)),
                10L,
                20
        );

        robot.holdForLowBattery(500L);

        assertThat(robot.isLowBatteryHold()).isTrue();
        assertThat(robot.hasLowBatteryAlert()).isTrue();
        assertThat(robot.isReturningToCharge(20)).isFalse();
    }

    @Test
    void lowBatteryRobotWithActiveTaskFinishesHandoverBeforeItIsHeld() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                101L,
                List.of(
                        new AiPlaybackContext.TimedStep(
                                "MOVE-LOADED", 1, AiPlaybackContext.StepType.MOVE,
                                0, 1_000, null, 10L, 20L, 301L, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "DROP", 2, AiPlaybackContext.StepType.SERVICE,
                                1_000, 2_000, 20L, null, null, 301L, "DROP"
                        ),
                        new AiPlaybackContext.TimedStep(
                                "MOVE-EGRESS", 3, AiPlaybackContext.StepType.MOVE,
                                2_000, 3_000, null, 20L, 30L, null, null
                        )
                ),
                10L,
                20
        );
        robot.setCurrentTaskId(301L);
        robot.setStepStarted(true);

        robot.holdForLowBattery(500L);
        robot.requestInitialHandover(500L);

        assertThat(robot.isHeld()).isFalse();
        assertThat(robot.isLowBatteryHold()).isFalse();
        assertThat(robot.hasLowBatteryAlert()).isTrue();
        assertThat(robot.getHandoverAtMillis()).isEqualTo(2_000L);
        assertThat(robot.getHandoverNodeId()).isEqualTo(20L);

        robot.hold(3_000L);
        assertThat(robot.isHeld()).isTrue();
        assertThat(robot.isLowBatteryHold()).isTrue();
        assertThat(robot.getHeldAtMillis()).isEqualTo(3_000L);
    }

    private AiPlaybackContext.TimedStep waitStep(int sequence, Long nodeId) {
        return new AiPlaybackContext.TimedStep(
                "WAIT-" + sequence,
                sequence,
                AiPlaybackContext.StepType.WAIT,
                sequence * 1_000L,
                (sequence + 1L) * 1_000L,
                nodeId,
                null,
                null,
                null,
                null,
                "collision avoidance"
        );
    }

    private AiPlaybackContext.TimedStep moveStep(
            int sequence,
            Long fromNodeId,
            Long toNodeId
    ) {
        return new AiPlaybackContext.TimedStep(
                "MOVE-" + sequence,
                sequence,
                AiPlaybackContext.StepType.MOVE,
                sequence * 1_000L,
                (sequence + 1L) * 1_000L,
                null,
                fromNodeId,
                toNodeId,
                null,
                null
        );
    }

    private AiPlaybackContext.TimedStep serviceStep(
            int sequence,
            Long nodeId,
            String serviceKind
    ) {
        return new AiPlaybackContext.TimedStep(
                "SERVICE-" + sequence,
                sequence,
                AiPlaybackContext.StepType.SERVICE,
                sequence * 1_000L,
                (sequence + 1L) * 1_000L,
                nodeId,
                null,
                null,
                null,
                serviceKind
        );
    }

    private AiPlaybackContext.TimedStep chargeStep(int sequence, Long nodeId) {
        return serviceStep(sequence, nodeId, "CHARGE");
    }
}
