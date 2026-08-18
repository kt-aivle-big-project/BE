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
    void simulationBatteryRatesAreAcceleratedTenTimes() {
        assertEquals(0.5, SimulationPlaybackService.simulationBatteryRate(0.05));
        assertEquals(1.5, SimulationPlaybackService.simulationBatteryRate(0.15));
        assertEquals(0.0, SimulationPlaybackService.simulationBatteryRate(null));

        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(new AiPlaybackContext.TimedStep(
                        "MOVE-1", 1, AiPlaybackContext.StepType.MOVE,
                        0, 1_000, null, 10L, 11L, null, null
                )),
                10L,
                100,
                SimulationPlaybackService.simulationBatteryRate(0.05),
                SimulationPlaybackService.simulationBatteryRate(0.15)
        );

        robot.consumeMoveBattery();
        assertEquals(100, robot.getBatteryLevel());

        robot.consumeWorkBattery();
        assertEquals(98, robot.getBatteryLevel());
    }

    @Test
    void lowBatteryAllowsDirectChargeRecoveryButReplansWhenBusinessWorkRemains() {
        AiPlaybackContext.RobotTimeline withoutCharge = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(new AiPlaybackContext.TimedStep(
                        "MOVE-1", 1, AiPlaybackContext.StepType.MOVE,
                        0, 1_000, null, 10L, 11L, null, null
                )),
                10L,
                20
        );
        AiPlaybackContext.RobotTimeline directChargeRecovery = new AiPlaybackContext.RobotTimeline(
                10002L,
                List.of(
                        new AiPlaybackContext.TimedStep(
                                "MOVE-2", 1, AiPlaybackContext.StepType.MOVE,
                                0, 1_000, null, 10L, 12L, null, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "CHARGE-2", 2, AiPlaybackContext.StepType.SERVICE,
                                1_000, 61_000, 12L, null, null, null, "CHARGE"
                        )
                ),
                10L,
                20
        );
        AiPlaybackContext.RobotTimeline businessBeforeCharge = new AiPlaybackContext.RobotTimeline(
                10003L,
                List.of(
                        new AiPlaybackContext.TimedStep(
                                "MOVE-3", 1, AiPlaybackContext.StepType.MOVE,
                                0, 1_000, null, 10L, 11L, null, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "PICKUP-3", 2, AiPlaybackContext.StepType.SERVICE,
                                1_000, 6_000, 11L, null, null, 301L, "PICKUP"
                        ),
                        new AiPlaybackContext.TimedStep(
                                "MOVE-4", 3, AiPlaybackContext.StepType.MOVE,
                                6_000, 7_000, null, 11L, 12L, null, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "CHARGE-3", 4, AiPlaybackContext.StepType.SERVICE,
                                7_000, 67_000, 12L, null, null, null, "CHARGE"
                        )
                ),
                10L,
                20
        );
        AiPlaybackContext.RobotTimeline chargingNow = new AiPlaybackContext.RobotTimeline(
                10004L,
                List.of(new AiPlaybackContext.TimedStep(
                        "CHARGE-4", 1, AiPlaybackContext.StepType.SERVICE,
                        0, 60_000, 12L, null, null, null, "CHARGE"
                )),
                12L,
                20
        );
        AiPlaybackContext.RobotTimeline depletedOnRecoveryRoute = new AiPlaybackContext.RobotTimeline(
                10005L,
                List.of(
                        new AiPlaybackContext.TimedStep(
                                "MOVE-5", 1, AiPlaybackContext.StepType.MOVE,
                                0, 1_000, null, 10L, 12L, null, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "CHARGE-5", 2, AiPlaybackContext.StepType.SERVICE,
                                1_000, 61_000, 12L, null, null, null, "CHARGE"
                        )
                ),
                10L,
                0
        );

        assertTrue(withoutCharge.needsLowBatteryReplan(20));
        assertFalse(directChargeRecovery.needsLowBatteryReplan(20));
        assertTrue(businessBeforeCharge.needsLowBatteryReplan(20));
        assertFalse(chargingNow.needsLowBatteryReplan(20));
        assertTrue(depletedOnRecoveryRoute.needsLowBatteryReplan(20));

        assertFalse(withoutCharge.isReturningToCharge(20));
        assertTrue(directChargeRecovery.isReturningToCharge(20));
        assertFalse(businessBeforeCharge.isReturningToCharge(20));
        assertFalse(chargingNow.isReturningToCharge(20));
        assertFalse(depletedOnRecoveryRoute.isReturningToCharge(20));

        withoutCharge.holdForLowBattery(3_000);
        assertTrue(withoutCharge.isHeld());
        assertEquals(3_000, withoutCharge.getLowBatteryWaitStartedAtMillis());
    }

    @Test
    void replanActivationKeepsClockMonotonicAndCarriesCurrentBatteryState() {
        AiPlaybackContext.RobotTimeline previousRobot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(),
                11L,
                73.0,
                0.5,
                1.5
        );
        previousRobot.setCarryingLoad(true);
        AiPlaybackContext previous = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-OLD", 1, "BE-RUN-1",
                5_000, 5_000, List.of(previousRobot), Set.of(20L), 1.0
        );
        AiPlaybackContext.TimedStep move = new AiPlaybackContext.TimedStep(
                "MOVE-NEW", 1, AiPlaybackContext.StepType.MOVE,
                1_000, 2_000, null, 11L, 12L, 20L, null,
                "replanned"
        );
        AiPlaybackContext.RobotTimeline plannedRobot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(move),
                11L,
                20.0,
                0.5,
                1.5
        );
        AiPlaybackContext pending = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-NEW", 2, "BE-RUN-1",
                1_000, 2_000, List.of(plannedRobot), Set.of(20L), 1.0
        );

        AiPlaybackContext activated = pending.rebaseForActivation(
                previous.getClockMillis(), previous);
        AiPlaybackContext.RobotTimeline activatedRobot = activated.getRobots().get(0);
        AiPlaybackContext.TimedStep activatedMove = activatedRobot.getSteps().get(0);

        assertEquals(5_000, activated.getClockMillis());
        assertEquals(6_000, activated.getMakespanMillis());
        assertEquals(5_000, activatedMove.startAtMillis());
        assertEquals(6_000, activatedMove.endAtMillis());
        assertEquals(73, activatedRobot.getBatteryLevel());
        assertTrue(activatedRobot.isCarryingLoad());
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
