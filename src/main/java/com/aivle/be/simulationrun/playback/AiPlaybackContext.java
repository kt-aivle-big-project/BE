package com.aivle.be.simulationrun.playback;

import com.aivle.be.robotstate.domain.RobotStatus;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    private volatile boolean handoverPlanning;
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
        if (handoverPlanning || quiescing && allRobotsHeld()) {
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
        handoverPlanning = true;
        quiesceRequestedAtMillis = clockMillis;
        try {
            Map<RobotTimeline, List<RobotTimeline.HandoverTarget>> candidates =
                    new LinkedHashMap<>();
            for (RobotTimeline robot : robots) {
                synchronized (robot) {
                    candidates.put(robot, robot.handoverCandidates(clockMillis));
                }
            }
            Map<RobotTimeline, RobotTimeline.HandoverTarget> selected =
                    selectConflictFreeHandoverTargets(candidates, clockMillis);
            for (RobotTimeline robot : robots) {
                synchronized (robot) {
                    RobotTimeline.HandoverTarget target = selected.get(robot);
                    if (target == null) {
                        robot.requestInitialHandover(clockMillis);
                    } else {
                        robot.applyHandover(target, clockMillis);
                    }
                }
            }
        } finally {
            handoverPlanning = false;
        }
    }

    /**
     * Selects a barrier where a robot that stops early is not left on a node
     * another old-plan robot still needs before reaching its own barrier.
     * Every candidate is a task-complete boundary, so extending a robot never
     * abandons a picked load halfway through its physical cycle.
     */
    private Map<RobotTimeline, RobotTimeline.HandoverTarget>
    selectConflictFreeHandoverTargets(
            Map<RobotTimeline, List<RobotTimeline.HandoverTarget>> candidates,
            long requestedAtMillis
    ) {
        List<RobotTimeline> ordered = new ArrayList<>(candidates.keySet());
        Map<RobotTimeline, RobotTimeline.HandoverTarget> selected =
                new LinkedHashMap<>();
        if (selectHandoverTarget(
                ordered, candidates, selected, 0, requestedAtMillis)) {
            return selected;
        }
        throw new IllegalStateException(
                "No conflict-free task-complete replan barrier is available"
        );
    }

    private boolean selectHandoverTarget(
            List<RobotTimeline> ordered,
            Map<RobotTimeline, List<RobotTimeline.HandoverTarget>> candidates,
            Map<RobotTimeline, RobotTimeline.HandoverTarget> selected,
            int index,
            long requestedAtMillis
    ) {
        if (index >= ordered.size()) {
            return true;
        }
        RobotTimeline robot = ordered.get(index);
        for (RobotTimeline.HandoverTarget candidate : candidates.get(robot)) {
            boolean conflict = selected.entrySet().stream().anyMatch(entry ->
                    hasTransitionConflict(
                            robot,
                            candidate,
                            entry.getKey(),
                            entry.getValue(),
                            requestedAtMillis
                    )
            );
            if (conflict) {
                continue;
            }
            selected.put(robot, candidate);
            if (selectHandoverTarget(
                    ordered,
                    candidates,
                    selected,
                    index + 1,
                    requestedAtMillis
            )) {
                return true;
            }
            selected.remove(robot);
        }
        return false;
    }

    private boolean hasTransitionConflict(
            RobotTimeline leftRobot,
            RobotTimeline.HandoverTarget left,
            RobotTimeline rightRobot,
            RobotTimeline.HandoverTarget right,
            long requestedAtMillis
    ) {
        if (Objects.equals(left.nodeId(), right.nodeId())) {
            return true;
        }
        return rightRobot.occupiesNodeBetween(
                left.nodeId(), left.atMillis(), right.atMillis(), requestedAtMillis)
                || leftRobot.occupiesNodeBetween(
                right.nodeId(), right.atMillis(), left.atMillis(), requestedAtMillis);
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
        return quiescing && allRobotsHeld();
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

    /**
     * A replan is produced from the clock and battery snapshot captured when
     * the request was sent.  The previous plan can continue until every robot
     * reaches its safe handover node, so activating that snapshot verbatim
     * would rewind both simulation time and battery state.  Move the complete
     * replacement timeline to the real activation clock and carry over the
     * physical state that changed while the replan was being calculated.
     */
    AiPlaybackContext rebaseForActivation(
            long activationClockMillis,
            AiPlaybackContext previous
    ) {
        long rebasedClock = Math.max(clockMillis, activationClockMillis);
        long offsetMillis = rebasedClock - clockMillis;
        Map<Long, RobotTimeline> previousByRobot = new HashMap<>();
        for (RobotTimeline robot : previous.robots) {
            previousByRobot.put(robot.robotId, robot);
        }

        List<RobotTimeline> rebasedRobots = robots.stream()
                .map(robot -> {
                    List<TimedStep> shiftedSteps = robot.steps.stream()
                            .map(step -> new TimedStep(
                                    step.stepId(),
                                    step.sequence(),
                                    step.type(),
                                    step.startAtMillis() + offsetMillis,
                                    step.endAtMillis() + offsetMillis,
                                    step.nodeId(),
                                    step.fromNodeId(),
                                    step.toNodeId(),
                                    step.taskId(),
                                    step.serviceKind(),
                                    step.reason()
                            ))
                            .toList();
                    RobotTimeline prior = previousByRobot.get(robot.robotId);
                    double activationBattery = prior == null
                            ? robot.batteryLevel
                            : prior.batteryLevel;
                    RobotTimeline shifted = new RobotTimeline(
                            robot.robotId,
                            shiftedSteps,
                            robot.currentNodeId,
                            activationBattery,
                            robot.moveBatteryRate,
                            robot.workBatteryRate
                    );
                    if (prior != null) {
                        shifted.carryingLoad = prior.carryingLoad;
                        shifted.lowBatteryAlert = prior.lowBatteryAlert;
                    }
                    return shifted;
                })
                .toList();

        return new AiPlaybackContext(
                simulationRunId,
                warehouseId,
                warehouseCode,
                planId,
                planVersion,
                simulationId,
                rebasedClock,
                makespanMillis + offsetMillis,
                rebasedRobots,
                taskIds,
                speed,
                chargingThreshold,
                chargingPowerByNode
        );
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
        private volatile int handoverAfterStepIndex = -1;
        private volatile boolean held;
        private boolean lowBatteryReplanRequested;
        private boolean lowBatteryHold;
        private boolean lowBatteryAlert;
        private long lowBatteryWaitStartedAtMillis;
        private Long heldAtMillis;
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
            lowBatteryAlert = true;
            lowBatteryWaitStartedAtMillis = Math.max(0, clockMillis);
            // Do not invalidate the active MAPF schedule by parking a robot in
            // the middle of its assigned physical cycle. requestQuiesce() will
            // let it reach the current task's unload/egress boundary first.
            // An idle robot has no such commitment and may stop immediately.
            if (currentTaskId == null) {
                lowBatteryHold = true;
                held = true;
                heldAtMillis = Math.max(0, clockMillis);
                status = RobotStatus.WAITING;
            }
        }

        private static double nonNegative(Double value) {
            return value == null || value < 0 ? 0.0 : value;
        }

        void requestInitialHandover(long clockMillis) {
            List<HandoverTarget> candidates = handoverCandidates(clockMillis);
            if (!candidates.isEmpty()) {
                HandoverTarget committedTask = candidates.get(0);
                applyHandover(committedTask, clockMillis);
                return;
            }
            applyHandover(new HandoverTarget(
                    clockMillis, currentNodeId, cursor - 1), clockMillis);
        }

        /**
         * Candidate handovers start at the final physical service of the
         * current task and may continue through egress MOVE/WAIT steps only
         * until the next SERVICE. A low-battery barrier must never make a
         * robot start another business task merely to find a parking node.
         */
        private List<HandoverTarget> handoverCandidates(long clockMillis) {
            List<HandoverTarget> result = new ArrayList<>();
            if (held || cursor >= steps.size()) {
                addCandidate(result, clockMillis, currentNodeId, cursor - 1);
                return result;
            }

            int firstCompletionIndex = -1;
            for (int index = cursor; index < steps.size(); index++) {
                TimedStep candidate = steps.get(index);
                if (currentTaskId != null
                        && currentTaskId.equals(candidate.taskId())
                        && candidate.type() == StepType.SERVICE
                        && completesPhysicalTask(candidate.serviceKind())) {
                    firstCompletionIndex = index;
                }
            }

            if (firstCompletionIndex < 0 && currentTaskId != null) {
                for (int index = cursor - 1; index >= 0; index--) {
                    TimedStep candidate = steps.get(index);
                    if (currentTaskId.equals(candidate.taskId())
                            && candidate.type() == StepType.SERVICE
                            && completesPhysicalTask(candidate.serviceKind())) {
                        firstCompletionIndex = index;
                        break;
                    }
                }
            }

            if (firstCompletionIndex >= cursor) {
                addCompletionAndEgressCandidates(result, firstCompletionIndex);
            } else {
                TimedStep active = currentStep();
                if (stepStarted && active != null
                        && active.type() == StepType.MOVE) {
                    // The previous physical task can already be complete while
                    // its task id remains attached to an in-flight egress MOVE.
                    // Parking at currentNodeId is impossible in that state: the
                    // robot has already left it. Finish the committed edge and
                    // hand over at its destination instead.
                    addCandidate(
                            result, active.endAtMillis(), active.toNodeId(), cursor);
                } else if (stepStarted && active != null
                        && active.type() == StepType.SERVICE) {
                    addCandidate(
                            result, active.endAtMillis(), active.nodeId(), cursor);
                } else {
                    addCandidate(result, clockMillis, currentNodeId, cursor - 1);
                }
                addEgressCandidates(result, cursor - 1);
            }

            if (result.isEmpty()) {
                TimedStep step = currentStep();
                if (stepStarted && step != null && step.type() == StepType.MOVE) {
                    addCandidate(
                            result, step.endAtMillis(), step.toNodeId(), cursor);
                } else if (stepStarted && step != null
                        && step.type() == StepType.SERVICE) {
                    addCandidate(
                            result, step.endAtMillis(), step.nodeId(), cursor);
                } else {
                    addCandidate(result, clockMillis, currentNodeId, cursor - 1);
                }
            }
            return result;
        }

        private void addCompletionAndEgressCandidates(
                List<HandoverTarget> result,
                int completionIndex
        ) {
            TimedStep completion = steps.get(completionIndex);
            addCandidate(
                    result,
                    completion.endAtMillis(),
                    completion.nodeId(),
                    completionIndex
            );
            addEgressCandidates(result, completionIndex);
        }

        private void addEgressCandidates(
                List<HandoverTarget> result,
                int completionIndex
        ) {
            if (completionIndex < 0 || completionIndex >= steps.size()) {
                return;
            }
            long handoverAt = steps.get(completionIndex).endAtMillis();
            Long handoverNode = steps.get(completionIndex).nodeId();
            for (int nextIndex = completionIndex + 1; nextIndex < steps.size(); nextIndex++) {
                TimedStep next = steps.get(nextIndex);
                if (next.type() == StepType.WAIT
                        && Objects.equals(next.nodeId(), handoverNode)) {
                    handoverAt = next.endAtMillis();
                    addCandidate(result, handoverAt, handoverNode, nextIndex);
                    continue;
                }
                if (next.type() == StepType.MOVE
                        && Objects.equals(next.fromNodeId(), handoverNode)) {
                    handoverAt = next.endAtMillis();
                    handoverNode = next.toNodeId();
                    addCandidate(result, handoverAt, handoverNode, nextIndex);
                    continue;
                }
                break;
            }
        }

        private void addCandidate(
                List<HandoverTarget> result,
                long atMillis,
                Long nodeId,
                int afterStepIndex
        ) {
            if (nodeId == null) {
                return;
            }
            HandoverTarget candidate = new HandoverTarget(
                    Math.max(0, atMillis), nodeId, afterStepIndex);
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }

        private boolean occupiesNodeBetween(
                Long nodeId,
                long afterMillis,
                long throughMillis,
                long requestedAtMillis
        ) {
            if (nodeId == null || throughMillis <= afterMillis) {
                return false;
            }

            TimedStep active = currentStep();
            if (Objects.equals(currentNodeId, nodeId)
                    && !(stepStarted && active != null
                    && active.type() == StepType.MOVE)) {
                long departure = firstDepartureFrom(
                        nodeId, cursor, throughMillis);
                if (intervalsOverlap(
                        requestedAtMillis, departure, afterMillis, throughMillis)) {
                    return true;
                }
            }

            for (int index = cursor; index < steps.size(); index++) {
                TimedStep step = steps.get(index);
                if (step.startAtMillis() > throughMillis) {
                    break;
                }
                if (step.type() == StepType.MOVE
                        && Objects.equals(step.toNodeId(), nodeId)) {
                    long departure = firstDepartureFrom(
                            nodeId, index + 1, throughMillis);
                    if (intervalsOverlap(
                            step.endAtMillis(), departure,
                            afterMillis, throughMillis)) {
                        return true;
                    }
                } else if ((step.type() == StepType.WAIT
                        || step.type() == StepType.SERVICE)
                        && Objects.equals(step.nodeId(), nodeId)
                        && intervalsOverlap(
                        step.startAtMillis(), step.endAtMillis(),
                        afterMillis, throughMillis)) {
                    return true;
                }
            }
            return false;
        }

        private long firstDepartureFrom(
                Long nodeId,
                int fromIndex,
                long defaultMillis
        ) {
            for (int index = Math.max(cursor, fromIndex); index < steps.size(); index++) {
                TimedStep step = steps.get(index);
                if (step.type() == StepType.MOVE
                        && Objects.equals(step.fromNodeId(), nodeId)) {
                    return step.startAtMillis();
                }
                if (step.type() == StepType.MOVE
                        || (step.nodeId() != null
                        && !Objects.equals(step.nodeId(), nodeId))) {
                    break;
                }
            }
            return defaultMillis;
        }

        private boolean intervalsOverlap(
                long leftStart,
                long leftEnd,
                long rightStart,
                long rightEnd
        ) {
            return leftStart < rightEnd && rightStart < leftEnd;
        }

        private static boolean completesPhysicalTask(String serviceKind) {
            return "DROP".equalsIgnoreCase(serviceKind)
                    || "STATION".equalsIgnoreCase(serviceKind)
                    || "EMPTY_TOTE_BUFFER".equalsIgnoreCase(serviceKind)
                    || "RETURN".equalsIgnoreCase(serviceKind);
        }

        /**
         * Returns true only for the last physical completion service belonging
         * to the BE task. Several AI task/cycle identifiers may be collapsed
         * into one BE task, so completing on the first DROP/STATION would make
         * the database get ahead of the robot. The last completion boundary is
         * also the safe point used by battery-replan handover selection.
         */
        boolean completesBeTaskAt(TimedStep completedStep) {
            if (completedStep == null
                    || completedStep.type() != StepType.SERVICE
                    || completedStep.taskId() == null
                    || !completesPhysicalTask(completedStep.serviceKind())) {
                return false;
            }
            for (int index = cursor + 1; index < steps.size(); index++) {
                TimedStep candidate = steps.get(index);
                if (completedStep.taskId().equals(candidate.taskId())
                        && candidate.type() == StepType.SERVICE
                        && completesPhysicalTask(candidate.serviceKind())) {
                    return false;
                }
            }
            return true;
        }

        void applyHandover(long atMillis, Long nodeId, long clockMillis) {
            int afterStepIndex = resolveHandoverStepIndex(atMillis, nodeId);
            applyHandover(
                    new HandoverTarget(atMillis, nodeId, afterStepIndex),
                    clockMillis
            );
        }

        private void applyHandover(HandoverTarget target, long clockMillis) {
            long atMillis = target.atMillis();
            Long nodeId = target.nodeId();
            handoverAtMillis = Math.max(0, atMillis);
            handoverNodeId = nodeId;
            handoverAfterStepIndex = target.afterStepIndex();
            boolean reached = !stepStarted
                    && cursor > handoverAfterStepIndex
                    && clockMillis >= handoverAtMillis
                    && (handoverNodeId == null || handoverNodeId.equals(currentNodeId));
            if (reached) {
                hold(clockMillis);
            }
        }

        boolean shouldHold(long clockMillis) {
            return handoverAtMillis != null
                    && !stepStarted
                    && cursor > handoverAfterStepIndex
                    && clockMillis >= handoverAtMillis
                    && (handoverNodeId == null || handoverNodeId.equals(currentNodeId));
        }

        private int resolveHandoverStepIndex(long atMillis, Long nodeId) {
            for (int index = cursor; index < steps.size(); index++) {
                TimedStep step = steps.get(index);
                Long completedNode = switch (step.type()) {
                    case MOVE -> step.toNodeId();
                    case WAIT, SERVICE -> step.nodeId();
                };
                if (step.endAtMillis() == atMillis
                        && Objects.equals(completedNode, nodeId)) {
                    return index;
                }
            }
            return cursor - 1;
        }

        void hold(long clockMillis) {
            if (lowBatteryReplanRequested) {
                lowBatteryHold = true;
            }
            held = true;
            heldAtMillis = Math.max(0, clockMillis);
            status = lowBatteryHold ? RobotStatus.WAITING : RobotStatus.IDLE;
        }

        void clearHandover() {
            handoverAtMillis = null;
            handoverNodeId = null;
            handoverAfterStepIndex = -1;
            held = lowBatteryHold;
            heldAtMillis = lowBatteryHold ? heldAtMillis : null;
            if (lowBatteryHold) {
                status = RobotStatus.WAITING;
            }
        }

        private record HandoverTarget(
                long atMillis,
                Long nodeId,
                int afterStepIndex
        ) {}
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
