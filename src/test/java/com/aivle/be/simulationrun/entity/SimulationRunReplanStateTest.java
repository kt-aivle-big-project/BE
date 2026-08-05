package com.aivle.be.simulationrun.entity;

import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationRunReplanStateTest {

    @Test
    void replanMovesThroughQuiescingAndPendingActivationBeforeRunningAgain() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 2, 12, 0);
        SimulationRun run = SimulationRun.create(null, now);

        run.start(now);
        run.startQuiescing();
        assertEquals(SimulationRunStatus.QUIESCING, run.getStatus());

        run.startReplanning();
        assertEquals(SimulationRunStatus.REPLANNING, run.getStatus());

        run.waitForPlanActivation();
        assertEquals(SimulationRunStatus.PENDING_ACTIVATION, run.getStatus());

        run.finishReplanning();
        assertEquals(SimulationRunStatus.RUNNING, run.getStatus());
    }
}
