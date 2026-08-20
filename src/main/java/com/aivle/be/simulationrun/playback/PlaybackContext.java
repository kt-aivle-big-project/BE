package com.aivle.be.simulationrun.playback;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robotstate.domain.RobotStatus;
import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class PlaybackContext {

    private final Long simulationRunId;
    private final Long warehouseId;

    // 창고 그래프 인접 리스트
    private final Map<Long, Set<Long>> adjacency;

    //
    private final Map<Long, List<Long>> accessNodes;

    private final List<RobotRuntime> robots;

    // 아직 발생하지 않은 작업 (발생 시각 오름차순)
    private final Deque<ScheduledTask> pendingTasks;

    // 발생했지만 아직 로봇에 배정되지 않은 작업
    private final Deque<Long> readyTaskIds = new ArrayDeque<>();

    // 시뮬레이션 내부 경과 시간(ms)
    private long clockMillis = 0L;

    // 배속을 곱하면서 생기는 1ms 미만의 잔여분.
    private double carryMillis = 0.0;

    private double speed;

    private boolean replanRequested = false;

    private long replanningSnapshotVersion = 0L;

    // 현재 snapshot에 결합된 재계획 요청 ID
    private String activeReplanId;

    private ReplanningState replanningState = ReplanningState.NONE;

    private RuntimeReoptimizationPlan installedReoptimizationPlan;

    private String activatedReplanId;

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

    public void changeSpeed(double newSpeed) {
        this.speed = newSpeed <= 0 ? 1.0 : newSpeed;
    }

    public void requestReplanning() {
        if (!replanRequested) {
            replanningSnapshotVersion++;
            activeReplanId = null;
            installedReoptimizationPlan = null;
            replanningState = ReplanningState.STOPPING;
        }
        this.replanRequested = true;
    }

    public boolean areAllRobotsStoppedForReplanning() {
        boolean stopped = robots.stream()
                .allMatch(RobotRuntime::isStoppedForReplanning);
        if (replanRequested && stopped
                && replanningState == ReplanningState.STOPPING) {
            replanningState = ReplanningState.FROZEN;
        }
        return stopped;
    }

    public ReplanningSnapshot captureReplanningSnapshot() {
        if (!replanRequested
                || !areAllRobotsStoppedForReplanning()) {
            throw new IllegalStateException(
                    "재계획 안전 정지가 완료되지 않았습니다."
            );
        }

        List<ReplanningSnapshot.RobotSnapshot> robotSnapshots =
                robots.stream()
                        .map(robot ->
                                new ReplanningSnapshot.RobotSnapshot(
                                        robot.getRobotId(),
                                        robot.getCurrentNodeId(),
                                        robot.getBatteryLevel(),
                                        robot.getStatus(),
                                        robot.getCurrentTaskId(),
                                        robot.getPhase(),
                                        robot.getBusyUntilMillis()
                                )
                        )
                        .toList();

        return new ReplanningSnapshot(
                activeReplanId,
                simulationRunId,
                replanningSnapshotVersion,
                clockMillis,
                pickingMillis,
                loadingMillis,
                robotSnapshots
        );
    }

    public boolean bindReplanId(
            Long snapshotVersion,
            String replanId
    ) {
        if (!replanRequested
                || replanningState != ReplanningState.FROZEN
                || replanningSnapshotVersion != snapshotVersion) {
            return false;
        }

        if (activeReplanId != null
                && !activeReplanId.equals(replanId)) {
            return false;
        }

        activeReplanId = replanId;
        return true;
    }

    public RuntimeReoptimizationPlan installReoptimizationPlan(
            RuntimeReoptimizationPlan plan
    ) {
        validateInstallCorrelation(plan);

        Map<Long, RuntimeRobotPlan> requestedPlans = new HashMap<>();
        Set<Long> taskIds = new HashSet<>();
        for (RuntimeRobotPlan robotPlan : plan.robotPlans()) {
            if (requestedPlans.put(robotPlan.robotId(), robotPlan) != null) {
                installFailed();
            }
            for (RuntimeTaskPlan taskPlan : robotPlan.taskPlans()) {
                if (!taskIds.add(taskPlan.taskId())) {
                    installFailed();
                }
            }
        }

        List<RobotRuntime> orderedRobots = robots.stream()
                .sorted(Comparator.comparing(RobotRuntime::getRobotId))
                .toList();
        Set<Long> participantRobotIds = orderedRobots.stream()
                .map(RobotRuntime::getRobotId)
                .collect(Collectors.toSet());
        if (!participantRobotIds.containsAll(requestedPlans.keySet())) {
            installFailed();
        }

        List<RobotRuntime.PreparedReoptimizationPlan> preparedPlans =
                new ArrayList<>();
        List<RuntimeRobotPlan> normalizedRobotPlans = new ArrayList<>();

        for (RobotRuntime robot : orderedRobots) {
            RuntimeRobotPlan robotPlan = requestedPlans.getOrDefault(
                    robot.getRobotId(),
                    new RuntimeRobotPlan(robot.getRobotId(), List.of())
            );
            boolean unavailable = robot.getStatus() == RobotStatus.ERROR
                    || robot.getStatus() == RobotStatus.OFFLINE;
            if (unavailable && !robotPlan.taskPlans().isEmpty()) {
                installFailed();
            }
            if (!unavailable
                    && (!robot.isPausedForReplanning()
                    || robot.getStatus() != RobotStatus.PAUSED)) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_RUNTIME_STATE_INVALID
                );
            }

            preparedPlans.add(robot.prepareReoptimizationPlan(
                    robotPlan,
                    plan.replanId(),
                    plan.snapshotVersion(),
                    activatedReplanId != null
                            && activatedReplanId.equals(
                            robot.getInstalledReplanId()
                    )
            ));
            normalizedRobotPlans.add(robotPlan);
        }

        RuntimeReoptimizationPlan normalized =
                new RuntimeReoptimizationPlan(
                        plan.simulationRunId(),
                        plan.replanId(),
                        plan.snapshotVersion(),
                        plan.simulationClockMillis(),
                        normalizedRobotPlans
                );
        if (replanningState == ReplanningState.PLAN_INSTALLED) {
            if (normalized.equals(installedReoptimizationPlan)) {
                return installedReoptimizationPlan;
            }
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_ALREADY_INSTALLED
            );
        }

        for (int index = 0; index < orderedRobots.size(); index++) {
            orderedRobots.get(index).installPreparedReoptimizationPlan(
                    preparedPlans.get(index)
            );
        }
        installedReoptimizationPlan = normalized;
        replanningState = ReplanningState.PLAN_INSTALLED;
        return installedReoptimizationPlan;
    }

    private void validateInstallCorrelation(
            RuntimeReoptimizationPlan plan
    ) {
        if (!replanRequested
                || replanningState != ReplanningState.FROZEN
                && replanningState != ReplanningState.PLAN_INSTALLED) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_STATE_INVALID
            );
        }
        if (!simulationRunId.equals(plan.simulationRunId())
                || !Objects.equals(activeReplanId, plan.replanId())
                || replanningSnapshotVersion != plan.snapshotVersion()
                || clockMillis != plan.simulationClockMillis()
                || !areAllRobotsStoppedForReplanning()) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE
            );
        }
    }

    private void installFailed() {
        throw new BusinessException(
                ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
        );
    }

    public void finishReplanning(String replanId) {
        if (Objects.equals(activatedReplanId, replanId)
                && replanningState == ReplanningState.ACTIVE) {
            return;
        }
        if (!Objects.equals(activeReplanId, replanId)
                || replanningState
                != ReplanningState.PLAN_INSTALLED) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE
            );
        }
        List<RobotRuntime> normalRobots = robots.stream()
                .filter(robot -> robot.getStatus() != RobotStatus.ERROR
                        && robot.getStatus() != RobotStatus.OFFLINE)
                .toList();
        if (normalRobots.stream().anyMatch(robot ->
                !robot.canActivateInstalledReoptimizationPlan(replanId))) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_STATE_INVALID
            );
        }
        normalRobots.forEach(robot ->
                robot.activateInstalledReoptimizationPlan(replanId)
        );
        this.replanRequested = false;
        this.activatedReplanId = activeReplanId;
        this.activeReplanId = null;
        this.replanningState = ReplanningState.ACTIVE;
    }

    public long advanceClock(long realMillis) {
        carryMillis += realMillis * speed;

        long advanced = (long) carryMillis;
        carryMillis -= advanced;
        clockMillis += advanced;

        return advanced;
    }

    public long clockSeconds() {
        return clockMillis / 1000L;
    }

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

    public boolean isFinished() {
        if (replanningState == ReplanningState.ACTIVE
                && activatedReplanId != null) {
            return robots.stream()
                    .filter(robot -> robot.getStatus() != RobotStatus.ERROR
                            && robot.getStatus() != RobotStatus.OFFLINE)
                    .allMatch(RobotRuntime::isReoptimizationPlanCompleted);
        }
        return pendingTasks.isEmpty()
                && readyTaskIds.isEmpty()
                && robots.stream().allMatch(RobotRuntime::isIdle);
    }

    private static long toMillis(double seconds, double defaultSeconds) {
        double value = seconds <= 0 ? defaultSeconds : seconds;
        return Math.round(value * 1000);
    }

    public record ScheduledTask(Long taskId, long releaseAtMillis) {
    }

    public enum ReplanningState {
        NONE,
        STOPPING,
        FROZEN,
        PLAN_INSTALLED,
        ACTIVE
    }
}
