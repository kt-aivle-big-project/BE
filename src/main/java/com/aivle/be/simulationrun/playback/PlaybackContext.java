package com.aivle.be.simulationrun.playback;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * 시뮬레이션 실행 1건의 재생 상태.
 * 내부 시계(clockMillis)를 기준으로 작업 발생과 로봇 동작이 진행된다.
 *
 * 시간 단위는 밀리초(ms)로 통일한다.
 * AI(cuOpt/MAPF) 계획이 ms 단위 타임라인으로 오기 때문에
 * 변환 없이 그대로 비교·재생할 수 있어야 한다.
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

    // 시뮬레이션 내부 경과 시간(ms)
    private long clockMillis = 0L;

    // 배속을 곱하면서 생기는 1ms 미만의 잔여분.
    // 버리지 않고 누적해야 저배속에서 시계가 느려지지 않는다.
    private double carryMillis = 0.0;

    // 실행 배속. 진행 중에도 변경할 수 있다.
    private double speed;

    // 전역 재계획을 위해 로봇들의 안전 정지를 요청한 상태
    private boolean replanRequested = false;

    // 동작별 소요 시간(ms)
    private final long moveMillisPerNode;
    private final long pickingMillis;
    private final long loadingMillis;

    // 충전 노드 ID -> 분당 충전량(%)
    private final Map<Long, Double> chargingPowerByNode;
    private final Set<Long> reservedChargingNodeIds = new HashSet<>();

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
            double loadingSeconds,
            Map<Long, Double> chargingPowerByNode
    ) {
        this.simulationRunId = simulationRunId;
        this.warehouseId = warehouseId;
        this.adjacency = adjacency;
        this.accessNodes = accessNodes;
        this.robots = robots;
        this.pendingTasks = new ArrayDeque<>(scheduledTasks);
        this.speed = speed <= 0 ? 1.0 : speed;
        this.moveMillisPerNode = toMillis(moveSecondsPerNode, 2.0);
        this.pickingMillis = toMillis(pickingSeconds, 5.0);
        this.loadingMillis = toMillis(loadingSeconds, 5.0);
        this.chargingPowerByNode = Map.copyOf(chargingPowerByNode);
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
     * 전체 로봇에 재계획 안전 정지를 요청한다.
     */
    public void requestReplanning() {
        this.replanRequested = true;
    }

    /**
     * 정상 로봇이 모두 재계획 정지 상태가 되었는지 확인한다.
     */
    public boolean areAllRobotsStoppedForReplanning() {
        return robots.stream()
                .allMatch(RobotRuntime::isStoppedForReplanning);
    }

    /**
     * 재계획 완료 후 모든 정상 로봇의 정지를 해제한다.
     */
    public void finishReplanning() {
        robots.forEach(RobotRuntime::resumeAfterReplanning);
        this.replanRequested = false;
    }

    /**
     * 시뮬레이션 시계를 전진시킨다.
     *
     * @param realMillis 실제 경과 시간(ms). 배속을 곱한 만큼 시뮬 시계가 흐른다.
     * @return 이번에 흐른 시뮬레이션 시간(ms)
     */
    public long advanceClock(long realMillis) {
        carryMillis += realMillis * speed;

        long advanced = (long) carryMillis;
        carryMillis -= advanced;
        clockMillis += advanced;

        return advanced;
    }

    /**
     * 시뮬레이션 시각을 초 단위로 반환한다. (로그 표시용)
     */
    public long clockSeconds() {
        return clockMillis / 1000L;
    }

    /**
     * 발생 시각이 된 작업을 대기열로 옮긴다.
     */
    public List<Long> releaseDueTasks() {
        List<Long> released = new ArrayList<>();

        while (!pendingTasks.isEmpty()
                && pendingTasks.peek().releaseAtMillis() <= clockMillis) {
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

    public boolean reserveChargingNode(Long nodeId) {
        return reservedChargingNodeIds.add(nodeId);
    }

    public void releaseChargingNode(Long nodeId) {
        if (nodeId != null) {
            reservedChargingNodeIds.remove(nodeId);
        }
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
     * 초 단위 설정값을 ms 로 변환한다. 값이 없거나 0 이하면 기본값을 쓴다.
     */
    private static long toMillis(double seconds, double defaultSeconds) {
        double value = seconds <= 0 ? defaultSeconds : seconds;
        return Math.round(value * 1000);
    }

    /**
     * 예약된 작업 하나. (발생 시각은 시뮬 시작 기준 ms)
     */
    public record ScheduledTask(Long taskId, long releaseAtMillis) {
    }
}
