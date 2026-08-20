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

    private Long previousNodeId;

    private Long currentTaskId;

    private Phase phase = Phase.IDLE;
    private RobotStatus status = RobotStatus.IDLE;

    // 재계획을 위해 안전 정지한 상태인지 여부
    private boolean pausedForReplanning = false;

    // 재계획 종료 후 복구할 화면 표시 상태
    private RobotStatus statusBeforeReplanningPause;

    private long busyUntilMillis = 0L;

    private long movementStartAtMillis = 0L;

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

    @Setter(AccessLevel.NONE)
    private ReoptimizationExecutionState reoptimizationExecutionState =
            ReoptimizationExecutionState.COMPLETED;

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

    void activateInstalledReoptimizationPlan(String replanId) {
        if (!replanId.equals(installedReplanId)) {
            throw new BusinessException(ErrorCode.REOPTIMIZATION_PLAN_STALE);
        }
        resumeAfterReplanning();
        remainingPath.clear();
        previousNodeId = null;
        currentPlanTaskIndex = installedTaskPlans.isEmpty() ? -1 : 0;
        currentPlanPathStepIndex = 0;
        currentPlanPathSegment = PlannedPathSegment.NONE;
        reoptimizationExecutionState = installedTaskPlans.isEmpty()
                ? ReoptimizationExecutionState.COMPLETED
                : ReoptimizationExecutionState.WAITING;
        RuntimeTaskPlan firstTask = currentRuntimeTaskPlan();
        phase = Phase.IDLE;
        status = RobotStatus.IDLE;
        currentTaskId = firstTask != null
                && firstTask.executionStage()
                == TaskPlan.ExecutionStage.TO_END
                ? firstTask.taskId()
                : null;
        if (reoptimizationExecutionState == ReoptimizationExecutionState.COMPLETED) {
            currentTaskId = null;
        }
    }

    boolean canActivateInstalledReoptimizationPlan(String replanId) {
        return replanId != null && replanId.equals(installedReplanId);
    }

    public RuntimeTaskPlan currentRuntimeTaskPlan() {
        return currentPlanTaskIndex < 0
                || currentPlanTaskIndex >= installedTaskPlans.size()
                ? null
                : installedTaskPlans.get(currentPlanTaskIndex);
    }

    public void transitionReoptimizationState(
            ReoptimizationExecutionState state
    ) {
        reoptimizationExecutionState = state;
    }

    public void setReoptimizationPathCursor(
            PlannedPathSegment segment,
            int stepIndex
    ) {
        currentPlanPathSegment = segment;
        currentPlanPathStepIndex = stepIndex;
    }

    public void completeCurrentRuntimeTask() {
        currentPlanTaskIndex++;
        currentPlanPathStepIndex = 0;
        currentPlanPathSegment = PlannedPathSegment.NONE;
        currentTaskId = null;
        if (currentPlanTaskIndex >= installedTaskPlans.size()) {
            currentPlanTaskIndex = -1;
            reoptimizationExecutionState = ReoptimizationExecutionState.COMPLETED;
            phase = Phase.IDLE;
            status = RobotStatus.IDLE;
        } else {
            reoptimizationExecutionState = ReoptimizationExecutionState.WAITING;
        }
    }

    public boolean isReoptimizationPlanCompleted() {
        return reoptimizationExecutionState
                == ReoptimizationExecutionState.COMPLETED;
    }

    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    public void moveTo(Long nodeId) {
        this.previousNodeId = this.currentNodeId;
        this.currentNodeId = nodeId;
    }

    public void stopMoving() {
        this.previousNodeId = null;
        this.movementStartAtMillis = 0L;
    }
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

    public void resumeAfterReplanning() {
        if (!pausedForReplanning) {
            return;
        }

        pausedForReplanning = false;

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

    public enum ReoptimizationExecutionState {
        WAITING,
        MOVING_TO_START,
        PICKING,
        MOVING_TO_END,
        DROPPING,
        COMPLETED
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
