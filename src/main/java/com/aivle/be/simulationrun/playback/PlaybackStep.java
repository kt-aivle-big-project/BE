package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;

/**
 * 재생 한 틱에 수행할 단위 동작.
 * 로봇이 어느 노드에서 어떤 상태가 되는지, 그리고 작업 상태를 함께 바꿔야 하는지를 담는다.
 */
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
