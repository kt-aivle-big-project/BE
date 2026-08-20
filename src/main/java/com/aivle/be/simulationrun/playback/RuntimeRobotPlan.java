package com.aivle.be.simulationrun.playback;

import java.util.List;
import java.util.Objects;

public record RuntimeRobotPlan(
        Long robotId,
        List<RuntimeTaskPlan> taskPlans
) {

    public RuntimeRobotPlan {
        Objects.requireNonNull(robotId, "robotId is required");
        taskPlans = taskPlans == null
                ? List.of()
                : List.copyOf(taskPlans);
    }
}
