package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;
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

    // 이 시뮬레이션 시각까지는 현재 동작을 수행 중 (그전에는 다음 동작으로 넘어가지 않음)
    private double busyUntilSeconds = 0.0;

    private double batteryLevel;

    // RobotSpec 기준 배터리 소모율
    private final double moveBatteryRate;
    private final double workBatteryRate;

    private Long chargingNodeId;
    private double chargingPowerPerMinute;

    // 남은 이동 경로
    private final Deque<Long> remainingPath = new ArrayDeque<>();

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

    public void charge(double simulatedSeconds) {
        if (simulatedSeconds <= 0 || chargingPowerPerMinute <= 0) {
            return;
        }
        batteryLevel = Math.min(
                100,
                batteryLevel + chargingPowerPerMinute * simulatedSeconds / 60.0
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
}
