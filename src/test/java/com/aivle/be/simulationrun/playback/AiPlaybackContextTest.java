package com.aivle.be.simulationrun.playback;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlaybackContextTest {

    @Test
    void stationServiceTransfersBoxOffMobileRobot() {
        assertFalse(SimulationPlaybackService.carryingLoadAfterServiceCompletion(true, "STATION"));
        assertTrue(SimulationPlaybackService.carryingLoadAfterServiceCompletion(false, "PICKUP"));
        assertTrue(SimulationPlaybackService.carryingLoadAfterServiceCompletion(true, "CHARGE"));
    }

    @Test
    void completesBeTaskOnlyAtLastPhysicalServiceForSameTask() {
        AiPlaybackContext.TimedStep station =
                timedService("STATION", 1, 0, 1_000, 20L, 301L);
        AiPlaybackContext.TimedStep emptyTote =
                timedService("EMPTY_TOTE_BUFFER", 2, 1_000, 2_000, 30L, 301L);
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(station, emptyTote),
                10L,
                100
        );

        assertFalse(robot.completesBeTaskAt(station));
        robot.advanceStep();
        assertTrue(robot.completesBeTaskAt(emptyTote));
    }

    @Test
    void completesBeTaskAtDropWhenNoLaterCompletionExists() {
        AiPlaybackContext.TimedStep drop =
                timedService("DROP", 1, 0, 1_000, 20L, 301L);
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(drop, timedMove("EGRESS", 2, 1_000, 2_000, 20L, 21L, 301L)),
                10L,
                100
        );

        assertTrue(robot.completesBeTaskAt(drop));
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
    void replanRequestWaitsUntilEveryRobotIsActuallyHeld() {
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
        moving.hold(1_000);
        assertEquals(1_000L, moving.getHeldAtMillis());
        assertFalse(context.readyForReplanRequest());
        assertFalse(context.allRobotsHeld());

        servicing.setStepStarted(false);
        servicing.advanceStep();
        assertTrue(servicing.shouldHold(2_000));
        servicing.hold(2_000);
        assertEquals(2_000L, servicing.getHeldAtMillis());

        assertTrue(context.readyForReplanRequest());
        assertTrue(context.allRobotsHeld());
    }

    @Test
    void quiescingFinishesCurrentTaskAtEarliestUncontestedSafeNode() {
        AiPlaybackContext.RobotTimeline robot = taskTimelineAtPickup();
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 1_500, 7_000,
                List.of(robot), Set.of(20L, 21L), 1.0
        );

        context.requestQuiesce();

        assertEquals(4_000, robot.getHandoverAtMillis());
        assertEquals(12L, robot.getHandoverNodeId());
        assertFalse(robot.shouldHold(3_999));

        robot.setCurrentNodeId(12L);
        robot.setStepStarted(false);
        // Merely arriving at the DROP node after a coarse clock tick does not
        // satisfy a handover whose boundary is after the DROP service itself.
        assertFalse(robot.shouldHold(4_000));
        robot.setCursor(4);
        assertTrue(robot.shouldHold(4_000));
    }

    @Test
    void quiescingAfterDropUsesTheObservedTaskCompleteNode() {
        AiPlaybackContext.RobotTimeline robot = taskTimelineAtPickup();
        robot.setCursor(4);
        robot.setStepStarted(false);
        robot.setCurrentNodeId(12L);
        robot.setCurrentTaskId(20L);
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 4_000, 7_000,
                List.of(robot), Set.of(20L, 21L), 1.0
        );

        context.requestQuiesce();

        assertEquals(4_000, robot.getHandoverAtMillis());
        assertEquals(12L, robot.getHandoverNodeId());
    }

    @Test
    void outboundTaskCompletesEmptyToteReturnBeforeReplanHandover() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(
                        timedService("PICKUP", 1, 0, 1_000, 10L, 20L),
                        timedMove("MOVE-STATION", 2, 1_000, 2_000, 10L, 20L, 20L),
                        timedService("STATION", 3, 2_000, 3_000, 20L, 20L),
                        timedMove("MOVE-TOTE", 4, 3_000, 4_000, 20L, 30L, 20L),
                        timedService("EMPTY_TOTE_BUFFER", 5, 4_000, 5_000, 30L, 20L),
                        timedMove("MOVE-EGRESS", 6, 5_000, 5_500, 30L, 31L, null)
                ),
                10L,
                100
        );
        robot.setStepStarted(true);
        robot.setCurrentTaskId(20L);
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 500, 5_500,
                List.of(robot), Set.of(20L), 1.0
        );

        context.requestQuiesce();

        assertEquals(5_000, robot.getHandoverAtMillis());
        assertEquals(30L, robot.getHandoverNodeId());
    }

    @Test
    void barrierMovesEarlyRobotOffNodeNeededByAnotherCommittedTask() {
        AiPlaybackContext.RobotTimeline early = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(
                        timedService("DROP", 1, 0, 2_000, 12L, 20L),
                        timedMove("EGRESS-1", 2, 2_000, 3_000, 12L, 13L, null),
                        timedMove("EGRESS-2", 3, 3_000, 4_000, 13L, 14L, null),
                        timedService("NEXT-PICKUP", 4, 4_000, 5_000, 14L, 22L)
                ),
                12L,
                20
        );
        early.setStepStarted(true);
        early.setCurrentTaskId(20L);

        AiPlaybackContext.RobotTimeline committed = new AiPlaybackContext.RobotTimeline(
                10002L,
                List.of(
                        timedMove("TO-SHARED", 1, 2_500, 3_500, 15L, 12L, 21L),
                        timedService("DROP", 2, 3_500, 4_500, 12L, 21L),
                        timedMove("LEAVE-SHARED", 3, 4_500, 5_000, 12L, 16L, null)
                ),
                15L,
                100
        );
        committed.setCurrentTaskId(21L);

        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 1_000, 5_000,
                List.of(early, committed), Set.of(20L, 21L), 1.0
        );

        context.requestQuiesce();

        // 12 is needed by the second robot after 2s, so the early low-battery
        // robot follows its already-safe MAPF egress and stops at 13 instead.
        assertEquals(3_000, early.getHandoverAtMillis());
        assertEquals(13L, early.getHandoverNodeId());
        assertEquals(4_500, committed.getHandoverAtMillis());
        assertEquals(12L, committed.getHandoverNodeId());
    }

    @Test
    void barrierNeverStartsAnotherTaskJustToClearAConflict() {
        AiPlaybackContext.RobotTimeline first = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(
                        timedService("DROP", 1, 0, 2_000, 12L, 20L),
                        timedService("NEXT-PICKUP", 2, 2_000, 3_000, 12L, 22L)
                ),
                12L,
                20
        );
        first.setStepStarted(true);
        first.setCurrentTaskId(20L);
        AiPlaybackContext.RobotTimeline second = new AiPlaybackContext.RobotTimeline(
                10002L,
                List.of(timedService("DROP", 1, 2_500, 4_000, 12L, 21L)),
                12L,
                100
        );
        second.setCurrentTaskId(21L);
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 1L, "WH-001", "PLAN-1", 1, "BE-RUN-1", 1_000, 4_000,
                List.of(first, second), Set.of(20L, 21L, 22L), 1.0
        );

        assertThrows(IllegalStateException.class, context::requestQuiesce);
        assertFalse(first.isHeld());
        assertFalse(second.isHeld());
    }

    private AiPlaybackContext.RobotTimeline taskTimelineAtPickup() {
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                10001L,
                List.of(
                        timedMove("MOVE-IN", 1, 0, 1_000, 10L, 11L, 20L),
                        timedService("PICKUP", 2, 1_000, 2_000, 11L, 20L),
                        timedMove("MOVE-LOADED", 3, 2_000, 3_000, 11L, 12L, 20L),
                        timedService("DROP", 4, 3_000, 4_000, 12L, 20L),
                        new AiPlaybackContext.TimedStep(
                                "WAIT-EGRESS", 5, AiPlaybackContext.StepType.WAIT,
                                4_000, 4_500, 12L, null, null, 20L, null
                        ),
                        timedMove("MOVE-EGRESS", 6, 4_500, 5_000, 12L, 13L, 21L),
                        timedService("NEXT-PICKUP", 7, 5_000, 7_000, 13L, 21L)
                ),
                11L,
                100
        );
        robot.setCursor(1);
        robot.setStepStarted(true);
        robot.setCurrentTaskId(20L);
        return robot;
    }

    private AiPlaybackContext.TimedStep timedMove(
            String id,
            int sequence,
            long start,
            long end,
            Long from,
            Long to,
            Long taskId
    ) {
        return new AiPlaybackContext.TimedStep(
                id, sequence, AiPlaybackContext.StepType.MOVE,
                start, end, null, from, to, taskId, null
        );
    }

    private AiPlaybackContext.TimedStep timedService(
            String kind,
            int sequence,
            long start,
            long end,
            Long node,
            Long taskId
    ) {
        return new AiPlaybackContext.TimedStep(
                kind, sequence, AiPlaybackContext.StepType.SERVICE,
                start, end, node, null, null, taskId, kind
        );
    }
}
