package com.aivle.be.simulationrun.playback;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.robotstate.domain.RobotStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 시뮬레이션 시각에 따라 움직이는 로봇 한 대의 실행 상태.
 */
@Getter
@Setter
public class RobotRuntime {

    public enum Phase {
        // 대기 (배정된 작업 없음)
        IDLE,

        // 작업 출발지로 이동 중
        MOVING_TO_START,

        // 집품 중
        PICKING,

        // 작업 도착지로 이동 중
        MOVING_TO_END,

        // 하역/적재 중
        DROPPING,

        // 충전소로 이동 중
        // 충전 중
        CHARGING
    }

    private final Long robotId;

    private Long currentNodeId;

    // 직전 노드. 이동 중일 때 화면 보간의 출발점으로 사용한다.
    private Long previousNodeId;

    private Long currentTaskId;

    private Phase phase = Phase.IDLE;
    private RobotStatus status = RobotStatus.IDLE;

    // 재계획을 위해 안전 정지한 상태인지 여부
    private boolean pausedForReplanning = false;

    // 재계획 종료 후 복구할 화면 표시 상태
    private RobotStatus statusBeforeReplanningPause;

    // 이 시뮬레이션 시각(ms)까지는 현재 동작을 수행 중.
    // 시계가 이 값을 넘어야 다음 동작으로 넘어간다.
    private long busyUntilMillis = 0L;

    private double batteryLevel;

    // RobotSpec 기준 배터리 소모율
    private final double moveBatteryRate;
    private final double workBatteryRate;

    private Long chargingNodeId;
    private double chargingPowerPerMinute;

    // 남은 이동 경로
    private final Deque<Long> remainingPath = new ArrayDeque<>();

    @Setter(AccessLevel.NONE)
    private String installedReplanId;

    @Setter(AccessLevel.NONE)
    private Long installedSnapshotVersion;

    @Setter(AccessLevel.NONE)
    private List<RuntimeTaskPlan> installedTaskPlans = List.of();

    @Setter(AccessLevel.NONE)
    private int currentPlanTaskIndex = -1;

    @Setter(AccessLevel.NONE)
    private PlannedPathSegment currentPlanPathSegment =
            PlannedPathSegment.NONE;

    @Setter(AccessLevel.NONE)
    private int currentPlanPathStepIndex = -1;

    public RobotRuntime(
            Long robotId,
            Long startNodeId,
            double startingBattery,
            Double moveBatteryRate,
            Double workBatteryRate
    ) {
        this.robotId = robotId;
        this.currentNodeId = startNodeId;
        this.batteryLevel = clampBattery(startingBattery);
        this.moveBatteryRate = nonNegativeRate(moveBatteryRate);
        this.workBatteryRate = nonNegativeRate(workBatteryRate);
    }

    public void setPath(List<Long> path) {
        remainingPath.clear();
        remainingPath.addAll(path);
    }

    public boolean hasRemainingPath() {
        return !remainingPath.isEmpty();
    }

    public Long pollNextNode() {
        return remainingPath.poll();
    }

    /**
     * 다음에 이동할 노드 (꺼내지 않고 확인만).
     * 화면 보간용으로 전송한다.
     */
    public Long peekNextNode() {
        return remainingPath.peek();
    }

    PreparedReoptimizationPlan prepareReoptimizationPlan(
            RuntimeRobotPlan robotPlan,
            String replanId,
            Long snapshotVersion,
            boolean mayReplaceActivatedPlan
    ) {
        if (!robotId.equals(robotPlan.robotId())) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
            );
        }

        List<RuntimeTaskPlan> taskPlans = robotPlan.taskPlans();
        for (int expected = 0; expected < taskPlans.size(); expected++) {
            RuntimeTaskPlan taskPlan = taskPlans.get(expected);
            boolean invalidFull = taskPlan.executionStage()
                    == TaskPlan.ExecutionStage.FULL
                    && taskPlan.pathToStart().isEmpty();
            boolean invalidToEnd = taskPlan.executionStage()
                    == TaskPlan.ExecutionStage.TO_END
                    && !taskPlan.pathToStart().isEmpty();
            if (taskPlan.sequence() != expected
                    || taskPlan.pathToEnd().isEmpty()
                    || invalidFull
                    || invalidToEnd) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
                );
            }
        }

        if (installedReplanId != null) {
            boolean samePlan = installedReplanId.equals(replanId)
                    && installedSnapshotVersion.equals(snapshotVersion)
                    && installedTaskPlans.equals(taskPlans);
            if (!samePlan && !mayReplaceActivatedPlan) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_ALREADY_INSTALLED
                );
            }
        }

        return new PreparedReoptimizationPlan(
                replanId,
                snapshotVersion,
                taskPlans,
                taskPlans.isEmpty() ? -1 : 0,
                PlannedPathSegment.NONE,
                -1
        );
    }

    void installPreparedReoptimizationPlan(
            PreparedReoptimizationPlan prepared
    ) {
        installedReplanId = prepared.replanId();
        installedSnapshotVersion = prepared.snapshotVersion();
        installedTaskPlans = prepared.taskPlans();
        currentPlanTaskIndex = prepared.currentTaskPlanIndex();
        currentPlanPathSegment = prepared.pathSegment();
        currentPlanPathStepIndex = prepared.pathStepIndex();
    }

    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    /**
     * 다음 노드로 진입한다. 직전 노드를 보간 출발점으로 기록한다.
     */
    public void moveTo(Long nodeId) {
        this.previousNodeId = this.currentNodeId;
        this.currentNodeId = nodeId;
    }

    /**
     * 정지 상태로 전환. 보간 출발점을 지운다.
     */
    public void stopMoving() {
        this.previousNodeId = null;
    }
    /**
     * 재계획을 위해 현재 안전 위치에서 정지한다.
     * phase와 remainingPath는 유지해 기존 진행 단계를 보존한다.
     */
    public void pauseForReplanning() {
        if (pausedForReplanning
                || status == RobotStatus.ERROR
                || status == RobotStatus.OFFLINE) {
            return;
        }

        statusBeforeReplanningPause = status;
        pausedForReplanning = true;
        status = RobotStatus.PAUSED;
        stopMoving();
    }

    /**
     * 재계획이 끝난 뒤 정지 전 상태로 복귀한다.
     */
    public void resumeAfterReplanning() {
        if (!pausedForReplanning) {
            return;
        }

        pausedForReplanning = false;

        // 재계획 대기 중 고장·오프라인이 됐다면 해당 상태를 유지한다.
        if (status == RobotStatus.ERROR
                || status == RobotStatus.OFFLINE) {
            statusBeforeReplanningPause = null;
            return;
        }

        status = statusBeforeReplanningPause == null
                ? RobotStatus.IDLE
                : statusBeforeReplanningPause;

        statusBeforeReplanningPause = null;
    }
    /**
     * 재계획을 시작할 수 있을 만큼 정지된 상태인지 확인한다.
     * 고장·오프라인 로봇은 이미 정지된 것으로 본다.
     */
    public boolean isStoppedForReplanning() {
        return pausedForReplanning
                || status == RobotStatus.ERROR
                || status == RobotStatus.OFFLINE;
    }


    public void consumeMoveBattery() {
        consumeBattery(moveBatteryRate);
    }

    public void consumeWorkBattery() {
        consumeBattery(workBatteryRate);
    }

    public boolean canMove() {
        return batteryLevel > 0;
    }

    public void assignChargingStation(Long nodeId, Double chargingPowerPerMinute) {
        this.chargingNodeId = nodeId;
        this.chargingPowerPerMinute = nonNegativeRate(chargingPowerPerMinute);
    }

    /**
     * 충전. 분당 충전량을 경과 시간(ms)만큼 적용한다.
     */
    public void charge(long simulatedMillis) {
        if (simulatedMillis <= 0 || chargingPowerPerMinute <= 0) {
            return;
        }
        batteryLevel = Math.min(
                100,
                batteryLevel + chargingPowerPerMinute * simulatedMillis / 60_000.0
        );
    }

    public boolean isFullyCharged() {
        return batteryLevel >= 100;
    }

    public void clearChargingStation() {
        chargingNodeId = null;
        chargingPowerPerMinute = 0;
    }

    public int batteryPercent() {
        return (int) Math.round(batteryLevel);
    }

    private void consumeBattery(double amount) {
        batteryLevel = Math.max(0, batteryLevel - amount);
    }

    private double clampBattery(double battery) {
        return Math.max(0, Math.min(100, battery));
    }

    private double nonNegativeRate(Double rate) {
        return rate == null ? 0 : Math.max(0, rate);
    }

    public enum PlannedPathSegment {
        NONE,
        TO_START,
        TO_END
    }

    record PreparedReoptimizationPlan(
            String replanId,
            Long snapshotVersion,
            List<RuntimeTaskPlan> taskPlans,
            int currentTaskPlanIndex,
            PlannedPathSegment pathSegment,
            int pathStepIndex
    ) {

        PreparedReoptimizationPlan {
            taskPlans = List.copyOf(taskPlans);
        }
    }
}
