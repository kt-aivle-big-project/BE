package com.aivle.be.simulationrun.playback;

import lombok.Getter;

import java.util.List;

@Getter
public class RobotPlan {

    private final Long robotId;
    private final List<PlaybackStep> steps;

    private int cursor = 0;
    private int batteryLevel = 100;

    public RobotPlan(Long robotId, List<PlaybackStep> steps, int startingBattery) {
        this.robotId = robotId;
        this.steps = steps;
        this.batteryLevel = startingBattery;
    }

    public boolean isFinished() {
        return cursor >= steps.size();
    }

    public PlaybackStep next() {
        PlaybackStep step = steps.get(cursor);
        cursor++;

        if (step.status() != null && step.status().isWorking()) {
            batteryLevel = Math.max(0, batteryLevel - 2);
        } else {
            batteryLevel = Math.max(0, batteryLevel - 1);
        }

        return step;
    }

    public int remaining() {
        return Math.max(0, steps.size() - cursor);
    }
}
