package com.aivle.be.simulationrun.playback;

import lombok.Getter;

import java.util.List;

/**
 * 로봇 한 대의 재생 계획.
 * 미리 계산된 단계 목록을 순서대로 소비한다.
 */
@Getter
public class RobotPlan {

    private final Long robotId;
    private final List<PlaybackStep> steps;

    private int cursor = 0;
    private int batteryLevel = 100;

    public RobotPlan(Long robotId, List<PlaybackStep> steps, int initialBattery) {
        this.robotId = robotId;
        this.steps = steps;
        this.batteryLevel = initialBattery;
    }

    public boolean isFinished() {
        return cursor >= steps.size();
    }

    /**
     * 다음 단계를 꺼내고 커서를 전진시킨다.
     * 이동 단계마다 배터리를 조금씩 소모한다.
     */
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
