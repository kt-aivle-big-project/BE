package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI가 만든 MOVE/WAIT/SERVICE 시간표를 원형 그대로 재생하기 위한 실행 상태다.
 */
@Getter
final class AiPlaybackContext {

    private final Long simulationRunId;
    private final Long warehouseId;
    private final String warehouseCode;
    private final String planId;
    private final Integer planVersion;
    private final String simulationId;
    private final long makespanMillis;
    private final List<RobotTimeline> robots;
    private final Set<Long> taskIds;
    private final int chargingThreshold;
    private final Map<Long, Double> chargingPowerByNode;

    private volatile long clockMillis;
    private double carryMillis;
    private double speed;
    private boolean tasksFinalized;
    private volatile boolean quiescing;
    private long quiesceRequestedAtMillis;

    AiPlaybackContext(
            Long simulationRunId,
            Long warehouseId,
            String warehouseCode,
            String planId,
            Integer planVersion,
            String simulationId,
            long initialClockMillis,
            long makespanMillis,
            List<RobotTimeline> robots,
            Set<Long> taskIds,
            double speed
    ) {
        this(
                simulationRunId,
                warehouseId,
                warehouseCode,
                planId,
                planVersion,
                simulationId,
                initialClockMillis,
                makespanMillis,
                robots,
                taskIds,
                speed,
                20,
                Map.of()
        );
    }

    AiPlaybackContext(
            Long simulationRunId,
            Long warehouseId,
            String warehouseCode,
            String planId,
            Integer planVersion,
            String simulationId,
            long initialClockMillis,
            long makespanMillis,
            List<RobotTimeline> robots,
            Set<Long> taskIds,
            double speed,
            int chargingThreshold,
            Map<Long, Double> chargingPowerByNode
    ) {
        this.simulationRunId = simulationRunId;
        this.warehouseId = warehouseId;
        this.warehouseCode = warehouseCode;
        this.planId = planId;
        this.planVersion = planVersion;
        this.simulationId = simulationId;
        this.clockMillis = Math.max(0, initialClockMillis);
        this.makespanMillis = Math.max(this.clockMillis, makespanMillis);
        this.robots = List.copyOf(robots);
        this.taskIds = Set.copyOf(taskIds);
        this.speed = speed <= 0 ? 1.0 : speed;
        this.chargingThreshold = Math.max(0, Math.min(100, chargingThreshold));
        this.chargingPowerByNode = Map.copyOf(chargingPowerByNode);
    }

    long advanceClock(long realMillis) {
        if (quiescing && allRobotsHeld()) {
            return 0;
        }
        carryMillis += realMillis * speed;
        long advanced = (long) carryMillis;
        carryMillis -= advanced;
        clockMillis += advanced;
        return advanced;
    }

    void changeSpeed(double newSpeed) {
        speed = newSpeed <= 0 ? 1.0 : newSpeed;
    }

    boolean isFinished() {
        return robots.stream().allMatch(RobotTimeline::isFinished)
                && clockMillis >= makespanMillis;
    }

    void markTasksFinalized() {
        tasksFinalized = true;
    }

    void requestQuiesce() {
        quiescing = true;
        quiesceRequestedAtMillis = clockMillis;
        for (RobotTimeline robot : robots) {
            synchronized (robot) {
                robot.requestInitialHandover(clockMillis);
            }
        }
    }

    void cancelQuiesce() {
        quiescing = false;
        for (RobotTimeline robot : robots) {
            synchronized (robot) {
                robot.clearHandover();
            }
        }
    }

    boolean readyForReplanRequest() {
        if (!quiescing) {
            return false;
        }
        for (RobotTimeline robot : robots) {
            synchronized (robot) {
                if (!robot.isHeld()
                        && !(robot.isStepStarted()
                        && robot.currentStep() != null
                        && robot.currentStep().type() == StepType.SERVICE)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean allRobotsHeld() {
        return robots.stream().allMatch(RobotTimeline::isHeld);
    }

    void applyHandover(Long robotId, long handoverAtMillis, Long handoverNodeId) {
        robots.stream()
                .filter(robot -> robotId.equals(robot.getRobotId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown replan robot: " + robotId))
                .applyHandover(handoverAtMillis, handoverNodeId, clockMillis);
    }

    double chargingPowerAt(Long nodeId) {
        if (nodeId == null) {
            return 0.0;
        }
        return Math.max(0.0, chargingPowerByNode.getOrDefault(nodeId, 0.0));
    }

    @Getter
    @Setter
    static final class RobotTimeline {
        private final Long robotId;
        private final List<TimedStep> steps;
        private volatile int cursor;
        private volatile boolean stepStarted;
        private volatile Long currentNodeId;
        private double batteryLevel;
        private final double moveBatteryRate;
        private final double workBatteryRate;
        private RobotStatus status = RobotStatus.IDLE;
        private Long currentTaskId;
        private boolean carryingLoad;
        private boolean failed;
        private volatile Long handoverAtMillis;
        private volatile Long handoverNodeId;
        private volatile boolean held;
        private boolean lowBatteryReplanRequested;
        private boolean lowBatteryHold;
        private boolean lowBatteryAlert;
        private long lowBatteryWaitStartedAtMillis;
        private long lastChargeUpdateAtMillis = -1L;

        RobotTimeline(
                Long robotId,
                List<TimedStep> steps,
                Long currentNodeId,
                int batteryLevel
        ) {
            this(robotId, steps, currentNodeId, batteryLevel, 1.0, 1.0);
        }

        RobotTimeline(
                Long robotId,
                List<TimedStep> steps,
                Long currentNodeId,
                double batteryLevel,
                Double moveBatteryRate,
                Double workBatteryRate
        ) {
            this.robotId = robotId;
            this.steps = List.copyOf(steps);
            this.currentNodeId = currentNodeId;
            this.batteryLevel = Math.max(0, Math.min(100, batteryLevel));
            this.moveBatteryRate = nonNegative(moveBatteryRate);
            this.workBatteryRate = nonNegative(workBatteryRate);
        }

        TimedStep currentStep() {
            return isFinished() ? null : steps.get(cursor);
        }

        Long nextMovementTargetNodeId() {
            for (int index = cursor + 1; index < steps.size(); index++) {
                TimedStep candidate = steps.get(index);
                if (candidate.type() == StepType.MOVE) {
                    return candidate.toNodeId();
                }
                if (candidate.type() == StepType.SERVICE) {
                    return candidate.nodeId();
                }
            }
            return currentNodeId;
        }

        void advanceStep() {
            cursor++;
            stepStarted = false;
        }

        boolean isFinished() {
            return failed || cursor >= steps.size();
        }

        void consumeMoveBattery() {
            batteryLevel = Math.max(0, batteryLevel - moveBatteryRate);
        }

        void consumeWorkBattery() {
            batteryLevel = Math.max(0, batteryLevel - workBatteryRate);
        }

        int getBatteryLevel() {
            return (int) Math.round(batteryLevel);
        }

        boolean isFullyCharged() {
            return batteryLevel >= 100.0;
        }

        void beginCharging(long stepStartAtMillis) {
            lastChargeUpdateAtMillis = Math.max(0, stepStartAtMillis);
            lowBatteryHold = false;
            held = false;
        }

        void chargeUntil(long clockMillis, double chargingPowerPerMinute) {
            if (lastChargeUpdateAtMillis < 0) {
                lastChargeUpdateAtMillis = Math.max(0, clockMillis);
                return;
            }
            long elapsedMillis = Math.max(0, clockMillis - lastChargeUpdateAtMillis);
            lastChargeUpdateAtMillis = Math.max(lastChargeUpdateAtMillis, clockMillis);
            if (elapsedMillis == 0 || chargingPowerPerMinute <= 0) {
                return;
            }
            batteryLevel = Math.min(
                    100.0,
                    batteryLevel + chargingPowerPerMinute * elapsedMillis / 60_000.0
            );
        }

        void finishCharging() {
            batteryLevel = Math.min(100.0, batteryLevel);
            lastChargeUpdateAtMillis = -1L;
            lowBatteryReplanRequested = false;
            lowBatteryHold = false;
            lowBatteryAlert = false;
        }

        void markLowBatteryAlert() {
            lowBatteryAlert = true;
        }

        void clearLowBatteryAlert() {
            lowBatteryAlert = false;
        }

        boolean hasLowBatteryAlert() {
            return lowBatteryAlert;
        }

        boolean needsLowBatteryReplan(int threshold) {
            if (stepStarted || lowBatteryReplanRequested || lowBatteryHold || isFinished()) {
                return false;
            }
            if (batteryLevel <= 0) {
                return true;
            }
            if (batteryLevel > threshold) {
                return false;
            }
            // 재계획으로 이미 MOVE/WAIT -> CHARGE 직접 복귀 경로를 받은 로봇은
            // 같은 저배터리 조건으로 다시 멈추지 않고 충전소까지 이동해야 한다.
            // 반대로 CHARGE 전에 PICKUP/DROP 같은 SERVICE가 남아 있으면
            // 해당 업무를 계속 수행하지 않도록 다시 안전 재계획 대상으로 둔다.
            return !isDirectChargeRecoveryRoute();
        }

        boolean isReturningToCharge(int threshold) {
            TimedStep step = currentStep();
            return !lowBatteryHold
                    && batteryLevel > 0
                    && batteryLevel <= threshold
                    && step != null
                    // A MAPF conflict may insert WAIT between MOVE steps.  It
                    // is still the same direct recovery trip and must remain
                    // visible as charging-station return, not ordinary idle.
                    && (step.type() == StepType.MOVE || step.type() == StepType.WAIT)
                    && isDirectChargeRecoveryRoute();
        }

        private boolean isDirectChargeRecoveryRoute() {
            for (int index = cursor; index < steps.size(); index++) {
                TimedStep candidate = steps.get(index);
                if (candidate.type() != StepType.SERVICE) {
                    continue;
                }
                return "CHARGE".equalsIgnoreCase(candidate.serviceKind());
            }
            return false;
        }

        void holdForLowBattery(long clockMillis) {
            lowBatteryReplanRequested = true;
            lowBatteryHold = true;
            lowBatteryAlert = true;
            lowBatteryWaitStartedAtMillis = Math.max(0, clockMillis);
            held = true;
            status = RobotStatus.WAITING;
        }

        private static double nonNegative(Double value) {
            return value == null || value < 0 ? 0.0 : value;
        }

        void requestInitialHandover(long clockMillis) {
            TimedStep step = currentStep();
            if (stepStarted && step != null && step.type() == StepType.MOVE) {
                applyHandover(step.endAtMillis(), step.toNodeId(), clockMillis);
            } else if (stepStarted && step != null && step.type() == StepType.SERVICE) {
                applyHandover(step.endAtMillis(), step.nodeId(), clockMillis);
            } else if (stepStarted && step != null && step.type() == StepType.WAIT) {
                handoverAtMillis = clockMillis;
                handoverNodeId = currentNodeId;
                held = true;
                status = RobotStatus.IDLE;
            } else {
                applyHandover(clockMillis, currentNodeId, clockMillis);
            }
        }

        void applyHandover(long atMillis, Long nodeId, long clockMillis) {
            handoverAtMillis = Math.max(0, atMillis);
            handoverNodeId = nodeId;
            held = !stepStarted
                    && clockMillis >= handoverAtMillis
                    && (handoverNodeId == null || handoverNodeId.equals(currentNodeId));
        }

        boolean shouldHold(long clockMillis) {
            return handoverAtMillis != null
                    && !stepStarted
                    && clockMillis >= handoverAtMillis
                    && (handoverNodeId == null || handoverNodeId.equals(currentNodeId));
        }

        void hold() {
            held = true;
            status = lowBatteryHold ? RobotStatus.WAITING : RobotStatus.IDLE;
        }

        void clearHandover() {
            handoverAtMillis = null;
            handoverNodeId = null;
            held = lowBatteryHold;
            if (lowBatteryHold) {
                status = RobotStatus.WAITING;
            }
        }
    }

    enum StepType {
        MOVE,
        WAIT,
        SERVICE
    }

    record TimedStep(
            String stepId,
            int sequence,
            StepType type,
            long startAtMillis,
            long endAtMillis,
            Long nodeId,
            Long fromNodeId,
            Long toNodeId,
            Long taskId,
            String serviceKind,
            String reason
    ) {
        TimedStep(
                String stepId,
                int sequence,
                StepType type,
                long startAtMillis,
                long endAtMillis,
                Long nodeId,
                Long fromNodeId,
                Long toNodeId,
                Long taskId,
                String serviceKind
        ) {
            this(
                    stepId,
                    sequence,
                    type,
                    startAtMillis,
                    endAtMillis,
                    nodeId,
                    fromNodeId,
                    toNodeId,
                    taskId,
                    serviceKind,
                    null
            );
        }
    }
}
