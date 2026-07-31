package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Getter
public class LaroPlaybackContext {

    private final Long simulationRunId;
    private final Long warehouseId;
    private final String planId;
    private final List<StepEvent> events;
    private final Map<String, Long> robotIds;
    private final Map<String, Long> nodeIds;
    private final Map<Long, RobotRuntime> robotRuntimes;

    private long clockMillis;
    private double carryMillis;
    private double speed;
    private int nextEventIndex;

    public LaroPlaybackContext(
            Long simulationRunId,
            Long warehouseId,
            String planId,
            List<ScheduledStep> steps,
            Map<String, Long> robotIds,
            Map<String, Long> nodeIds,
            Map<Long, RobotRuntime> robotRuntimes,
            double speed
    ) {
        this.simulationRunId = simulationRunId;
        this.warehouseId = warehouseId;
        this.planId = planId;
        this.events = buildEvents(steps);
        this.robotIds = Map.copyOf(robotIds);
        this.nodeIds = Map.copyOf(nodeIds);
        this.robotRuntimes = Map.copyOf(robotRuntimes);
        this.speed = speed <= 0 ? 1.0 : speed;
    }

    public long advanceClock(long realMillis) {
        carryMillis += realMillis * speed;
        long advanced = (long) carryMillis;
        carryMillis -= advanced;
        clockMillis += advanced;
        return advanced;
    }

    public void changeSpeed(double newSpeed) {
        speed = newSpeed <= 0 ? 1.0 : newSpeed;
    }

    public List<StepEvent> pollDueEvents() {
        List<StepEvent> due = new ArrayList<>();

        while (nextEventIndex < events.size()) {
            StepEvent event = events.get(nextEventIndex);
            long eventTime = event.eventTimeMs();

            if (eventTime > clockMillis) {
                break;
            }

            due.add(event);
            nextEventIndex++;
        }

        return due;
    }

    public boolean isFinished() {
        return nextEventIndex >= events.size();
    }

    public static List<ScheduledStep> flatten(
            List<LaroPlanResponse.RobotPlan> robotPlans
    ) {
        List<ScheduledStep> flattened = new ArrayList<>();

        for (LaroPlanResponse.RobotPlan robotPlan : robotPlans) {
            if (robotPlan.steps() == null) {
                continue;
            }
            for (LaroPlanResponse.PlanStep step : robotPlan.steps()) {
                flattened.add(new ScheduledStep(robotPlan.robotId(), step));
            }
        }

        flattened.sort(
                Comparator.comparingLong(
                                (ScheduledStep scheduled) ->
                                        scheduled.step().startAtMs()
                        )
                        .thenComparing(scheduled -> scheduled.robotExternalId())
                        .thenComparingInt(scheduled -> scheduled.step().sequence())
        );

        return flattened;
    }

    private static List<StepEvent> buildEvents(List<ScheduledStep> steps) {
        List<StepEvent> events = new ArrayList<>();
        for (ScheduledStep scheduled : steps) {
            events.add(new StepEvent(
                    scheduled,
                    true,
                    scheduled.step().startAtMs()
            ));
            events.add(new StepEvent(
                    scheduled,
                    false,
                    scheduled.step().endAtMs()
            ));
        }
        events.sort(
                Comparator.comparingLong(StepEvent::eventTimeMs)
                        .thenComparing(event -> !event.start())
                        .thenComparing(event ->
                                event.scheduledStep().robotExternalId())
                        .thenComparingInt(event ->
                                event.scheduledStep().step().sequence())
        );
        return List.copyOf(events);
    }

    public record ScheduledStep(
            String robotExternalId,
            LaroPlanResponse.PlanStep step
    ) {
    }

    public record StepEvent(
            ScheduledStep scheduledStep,
            boolean start,
            long eventTimeMs
    ) {
    }
}
