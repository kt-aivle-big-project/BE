package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Repository-free, immutable runtime representation of a DB-applied plan. */
public record RuntimeReoptimizationPlan(
        Long simulationRunId,
        String replanId,
        Long snapshotVersion,
        Long simulationClockMillis,
        List<RuntimeRobotPlan> robotPlans
) {

    public RuntimeReoptimizationPlan {
        Objects.requireNonNull(
                simulationRunId,
                "simulationRunId is required"
        );
        Objects.requireNonNull(replanId, "replanId is required");
        Objects.requireNonNull(
                snapshotVersion,
                "snapshotVersion is required"
        );
        Objects.requireNonNull(
                simulationClockMillis,
                "simulationClockMillis is required"
        );
        robotPlans = robotPlans == null
                ? List.of()
                : List.copyOf(robotPlans);
    }

    public static RuntimeReoptimizationPlan from(
            ReoptimizationActivationPlan activationPlan
    ) {
        Objects.requireNonNull(
                activationPlan,
                "activationPlan is required"
        );

        List<RuntimeRobotPlan> robotPlans = activationPlan.taskPlans()
                .stream()
                .collect(Collectors.groupingBy(
                        ReoptimizationActivationPlan
                                .TaskPlanView::robotId
                ))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new RuntimeRobotPlan(
                        entry.getKey(),
                        entry.getValue().stream()
                                .sorted(Comparator.comparingInt(
                                        ReoptimizationActivationPlan
                                                .TaskPlanView::sequence
                                ))
                                .map(RuntimeReoptimizationPlan::toTaskPlan)
                                .toList()
                ))
                .toList();

        return new RuntimeReoptimizationPlan(
                activationPlan.simulationRunId(),
                activationPlan.replanId(),
                activationPlan.snapshotVersion(),
                activationPlan.simulationClockMillis(),
                robotPlans
        );
    }

    private static RuntimeTaskPlan toTaskPlan(
            ReoptimizationActivationPlan.TaskPlanView taskPlan
    ) {
        return new RuntimeTaskPlan(
                taskPlan.taskId(),
                taskPlan.sequence(),
                taskPlan.executionStage(),
                taskPlan.pathToStart().stream()
                        .map(RuntimeReoptimizationPlan::toPathStep)
                        .toList(),
                taskPlan.pathToEnd().stream()
                        .map(RuntimeReoptimizationPlan::toPathStep)
                        .toList(),
                taskPlan.estimatedStartTimeMillis(),
                taskPlan.estimatedCompletionTimeMillis()
        );
    }

    private static RuntimePathStep toPathStep(
            ReoptimizationActivationPlan.PathStepView pathStep
    ) {
        return new RuntimePathStep(
                pathStep.nodeId(),
                pathStep.arrivalTimeMillis(),
                pathStep.departureTimeMillis()
        );
    }
}
