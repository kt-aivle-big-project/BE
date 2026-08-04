package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;

import java.util.List;

/**
 * 모든 정상 로봇이 안전 정지한 시점의 불변 Runtime snapshot.
 */
public record ReplanningSnapshot(
        String replanId,
        Long simulationRunId,
        Long snapshotVersion,
        Long simulationClockMillis,
        Long pickingDurationMillis,
        Long droppingDurationMillis,
        List<RobotSnapshot> robots
) {

    public ReplanningSnapshot {
        robots = robots == null ? List.of() : List.copyOf(robots);
    }

    public ReplanningSnapshot(
            String replanId,
            Long simulationRunId,
            Long snapshotVersion,
            Long simulationClockMillis,
            List<RobotSnapshot> robots
    ) {
        this(
                replanId,
                simulationRunId,
                snapshotVersion,
                simulationClockMillis,
                null,
                null,
                robots
        );
    }

    public record RobotSnapshot(
            Long robotId,
            Long currentNodeId,
            Double batteryLevel,
            RobotStatus status,
            Long currentTaskId,
            RobotRuntime.Phase runtimePhase,
            Long busyUntilMillis
    ) {
    }
}
