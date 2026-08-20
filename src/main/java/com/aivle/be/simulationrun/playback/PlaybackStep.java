package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;

public record PlaybackStep(
        Long nodeId,
        RobotStatus status,
        Long taskId,
        Action action
) {
    public enum Action {
        // 로봇 상태만 갱신
        NONE,

        // 이 단계에서 작업을 시작 처리
        START_TASK,

        // 이 단계에서 작업을 완료 처리
        COMPLETE_TASK
    }

    public static PlaybackStep move(Long nodeId, Long taskId) {
        return new PlaybackStep(nodeId, RobotStatus.MOVING, taskId, Action.NONE);
    }

    public static PlaybackStep of(Long nodeId, RobotStatus status, Long taskId) {
        return new PlaybackStep(nodeId, status, taskId, Action.NONE);
    }

    public static PlaybackStep withAction(
            Long nodeId,
            RobotStatus status,
            Long taskId,
            Action action
    ) {
        return new PlaybackStep(nodeId, status, taskId, action);
    }
}
