package com.aivle.be.simulationrun.playback;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlaybackContextTest {

    @Test
    void stationServiceTransfersBoxOffMobileRobot() {
        assertFalse(SimulationPlaybackService.carryingLoadAfterServiceCompletion(true, "STATION"));
        assertTrue(SimulationPlaybackService.carryingLoadAfterServiceCompletion(false, "PICKUP"));
        assertTrue(SimulationPlaybackService.carryingLoadAfterServiceCompletion(true, "CHARGE"));
    }

    @Test
    void advancesUsingSimulationSpeedAndFinishesOnlyAfterTimelineEnd() {
        AiPlaybackContext.TimedStep move = new AiPlaybackContext.TimedStep(
                "R10001-1",
                1,
                AiPlaybackContext.StepType.MOVE,
                0,
                1_000,
                null,
                10L,
                11L,
                20L,
                null
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(move),
                10L,
                100
        );
        AiPlaybackContext context = new AiPlaybackContext(
                1L,
                1L,
                "WH-001",
                "PLAN-1",
                1,
                "BE-RUN-1",
                0,
                1_000,
                List.of(robot),
                Set.of(20L),
                2.0
        );

        assertEquals(500, context.advanceClock(250));
        assertFalse(context.isFinished());

        robot.advanceStep();
        assertEquals(500, context.advanceClock(250));
        assertTrue(context.isFinished());
    }

    @Test
    void runtimeSpeedCanBeChangedWithoutResettingClock() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(),
                10L,
                100
        );
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 0, 1_500,
                List.of(robot), Set.of(), 1.0
        );

        context.advanceClock(500);
        context.changeSpeed(2.0);
        context.advanceClock(500);

        assertEquals(1_500, context.getClockMillis());
        assertTrue(context.isFinished());
    }

    @Test
    void aiTimelineUsesRobotBatteryRatesAndChargesWithSimulationTime() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(new AiPlaybackContext.TimedStep(
                        "MOVE-1", 1, AiPlaybackContext.StepType.MOVE,
                        0, 1_000, null, 10L, 11L, null, null
                )),
                10L,
                25.0,
                2.5,
                1.5
        );

        robot.consumeMoveBattery();
        robot.consumeWorkBattery();
        assertEquals(21, robot.getBatteryLevel());

        robot.beginCharging(0);
        robot.chargeUntil(60_000, 20.0);
        assertEquals(41, robot.getBatteryLevel());
    }

    @Test
    void lowBatteryRequestsReplanOnlyWhenNoChargeLegIsAlreadyPlanned() {
        AiPlaybackContext.RobotTimeline withoutCharge = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(new AiPlaybackContext.TimedStep(
                        "MOVE-1", 1, AiPlaybackContext.StepType.MOVE,
                        0, 1_000, null, 10L, 11L, null, null
                )),
                10L,
                20
        );
        AiPlaybackContext.RobotTimeline withCharge = new AiPlaybackContext.RobotTimeline(
                10002L,
                List.of(new AiPlaybackContext.TimedStep(
                        "CHARGE-1", 1, AiPlaybackContext.StepType.SERVICE,
                        0, 60_000, 12L, null, null, null, "CHARGE"
                )),
                12L,
                20
        );

        assertTrue(withoutCharge.needsLowBatteryReplan(20));
        assertFalse(withCharge.needsLowBatteryReplan(20));

        withoutCharge.holdForLowBattery(3_000);
        assertTrue(withoutCharge.isHeld());
        assertEquals(3_000, withoutCharge.getLowBatteryWaitStartedAtMillis());
    }

    @Test
    void quiescingWaitsForCurrentMoveButAllowsCurrentServiceToFinish() {
        AiPlaybackContext.RobotTimeline moving = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(new AiPlaybackContext.TimedStep(
                        "MOVE-1", 1, AiPlaybackContext.StepType.MOVE,
                        0, 1_000, null, 10L, 11L, 20L, null
                )),
                10L,
                100
        );
        moving.setStepStarted(true);
        AiPlaybackContext.RobotTimeline servicing = new AiPlaybackContext.RobotTimeline(
                10002L,
                List.of(new AiPlaybackContext.TimedStep(
                        "SERVICE-1", 1, AiPlaybackContext.StepType.SERVICE,
                        0, 2_000, 12L, null, null, 21L, "PICKUP"
                )),
                12L,
                100
        );
        servicing.setStepStarted(true);
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 0, 2_000,
                List.of(moving, servicing), Set.of(20L, 21L), 1.0
        );

        context.requestQuiesce();

        assertFalse(context.readyForReplanRequest());
        moving.setCurrentNodeId(11L);
        moving.setStepStarted(false);
        moving.advanceStep();
        assertTrue(moving.shouldHold(1_000));
        moving.hold();
        assertTrue(context.readyForReplanRequest());
        assertFalse(context.allRobotsHeld());
    }
}
