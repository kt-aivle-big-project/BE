package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
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
                if (!robot.isHeld()) {
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

    @Getter
    @Setter
    static final class RobotTimeline {
        private final Long robotId;
        private final List<TimedStep> steps;
        private volatile int cursor;
        private volatile boolean stepStarted;
        private volatile Long currentNodeId;
        private int batteryLevel;
        private RobotStatus status = RobotStatus.IDLE;
        private Long currentTaskId;
        private boolean carryingLoad;
        private boolean failed;
        private volatile Long handoverAtMillis;
        private volatile Long handoverNodeId;
        private volatile boolean held;

        RobotTimeline(
                Long robotId,
                List<TimedStep> steps,
                Long currentNodeId,
                int batteryLevel
        ) {
            this.robotId = robotId;
            this.steps = List.copyOf(steps);
            this.currentNodeId = currentNodeId;
            this.batteryLevel = Math.max(0, Math.min(100, batteryLevel));
        }

        TimedStep currentStep() {
            return isFinished() ? null : steps.get(cursor);
        }

        void advanceStep() {
            cursor++;
            stepStarted = false;
        }

        boolean isFinished() {
            return failed || cursor >= steps.size();
        }

        void consumeMoveBattery() {
            batteryLevel = Math.max(0, batteryLevel - 1);
        }

        void consumeWorkBattery() {
            batteryLevel = Math.max(0, batteryLevel - 1);
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
            status = RobotStatus.IDLE;
        }

        void clearHandover() {
            handoverAtMillis = null;
            handoverNodeId = null;
            held = false;
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
            String serviceKind
    ) {
    }
}
