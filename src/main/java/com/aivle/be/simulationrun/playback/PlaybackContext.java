package com.aivle.be.simulationrun.playback;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시뮬레이션 실행 1건의 재생 상태.
 * 내부 시계(clockSeconds)를 기준으로 작업 발생과 로봇 동작이 진행된다.
 */
@Getter
public class PlaybackContext {

    private final Long simulationRunId;
    private final Long warehouseId;

    // 창고 그래프 인접 리스트
    private final Map<Long, Set<Long>> adjacency;

    // 랙 노드 -> 그 랙에 접근할 수 있는 통로 노드 목록
    //
    // 로봇은 랙 안으로 들어가지 않고 앞 통로에 서서 작업한다.
    // (실제 창고에서도 로봇이 선반 안으로 들어가지 않는다)
    private final Map<Long, List<Long>> accessNodes;

    private final List<RobotRuntime> robots;

    // 아직 발생하지 않은 작업 (발생 시각 오름차순)
    private final Deque<ScheduledTask> pendingTasks;

    // 발생했지만 아직 로봇에 배정되지 않은 작업
    private final Deque<Long> readyTaskIds = new ArrayDeque<>();

    // 시뮬레이션 내부 경과 시간(초)
    private double clockSeconds = 0.0;

    // 실행 배속. 진행 중에도 변경할 수 있다.
    private double speed;

    // 동작별 소요 시간(초)
    private final double moveSecondsPerNode;
    private final double pickingSeconds;
    private final double loadingSeconds;

    public PlaybackContext(
            Long simulationRunId,
            Long warehouseId,
            Map<Long, Set<Long>> adjacency,
            Map<Long, List<Long>> accessNodes,
            List<RobotRuntime> robots,
            List<ScheduledTask> scheduledTasks,
            double speed,
            double moveSecondsPerNode,
            double pickingSeconds,
            double loadingSeconds
    ) {
        this.simulationRunId = simulationRunId;
        this.warehouseId = warehouseId;
        this.adjacency = adjacency;
        this.accessNodes = accessNodes;
        this.robots = robots;
        this.pendingTasks = new ArrayDeque<>(scheduledTasks);
        this.speed = speed <= 0 ? 1.0 : speed;
        this.moveSecondsPerNode = moveSecondsPerNode <= 0 ? 2.0 : moveSecondsPerNode;
        this.pickingSeconds = pickingSeconds <= 0 ? 5.0 : pickingSeconds;
        this.loadingSeconds = loadingSeconds <= 0 ? 5.0 : loadingSeconds;
    }

    /**
     * 실행 배속을 변경한다.
     *
     * 시계 전진 속도만 바뀌므로 이미 진행 중인 동작도
     * 남은 시간이 그만큼 빠르게/느리게 소진된다.
     */
    public void changeSpeed(double newSpeed) {
        this.speed = newSpeed <= 0 ? 1.0 : newSpeed;
    }

    /**
     * 시뮬레이션 시계를 전진시킨다.
     */
    public void advanceClock(double realSeconds) {
        clockSeconds += realSeconds * speed;
    }

    /**
     * 발생 시각이 된 작업을 대기열로 옮긴다.
     */
    public List<Long> releaseDueTasks() {
        List<Long> released = new ArrayList<>();

        while (!pendingTasks.isEmpty()
                && pendingTasks.peek().releaseAtSeconds() <= clockSeconds) {
            ScheduledTask task = pendingTasks.poll();
            readyTaskIds.add(task.taskId());
            released.add(task.taskId());
        }

        return released;
    }

    public Long pollReadyTask() {
        return readyTaskIds.poll();
    }

    public boolean hasReadyTask() {
        return !readyTaskIds.isEmpty();
    }

    /**
     * 모든 작업이 발생했고, 대기열도 비었고, 모든 로봇이 유휴 상태인가.
     */
    public boolean isFinished() {
        return pendingTasks.isEmpty()
                && readyTaskIds.isEmpty()
                && robots.stream().allMatch(RobotRuntime::isIdle);
    }

    /**
     * 예약된 작업 하나.
     */
    public record ScheduledTask(Long taskId, int releaseAtSeconds) {
    }
}
