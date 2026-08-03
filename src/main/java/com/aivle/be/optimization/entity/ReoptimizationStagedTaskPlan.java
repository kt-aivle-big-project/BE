package com.aivle.be.optimization.entity;

import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "reoptimization_staged_task_plans",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reopt_stage_task",
                        columnNames = {"stage_id", "task_id"}
                ),
                @UniqueConstraint(
                        name = "uk_reopt_stage_robot_sequence",
                        columnNames = {
                                "stage_id",
                                "robot_id",
                                "plan_sequence"
                        }
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReoptimizationStagedTaskPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ReoptimizationPlanStage stage;

    @Column(name = "robot_id", nullable = false)
    private Long robotId;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "plan_sequence", nullable = false)
    private Integer sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_stage", nullable = false, length = 20)
    private TaskPlan.ExecutionStage executionStage;

    @Column(name = "estimated_start_time_millis", nullable = false)
    private Long estimatedStartTimeMillis;

    @Column(name = "estimated_completion_time_millis", nullable = false)
    private Long estimatedCompletionTimeMillis;

    @OneToMany(
            mappedBy = "taskPlan",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("segmentOrder ASC, stepSequence ASC")
    private List<ReoptimizationStagedPathStep> pathSteps =
            new ArrayList<>();

    static ReoptimizationStagedTaskPlan create(
            ReoptimizationPlanStageCommand.TaskPlanCommand command
    ) {
        ReoptimizationStagedTaskPlan taskPlan =
                new ReoptimizationStagedTaskPlan();
        taskPlan.robotId = command.robotId();
        taskPlan.taskId = command.taskId();
        taskPlan.sequence = command.sequence();
        taskPlan.executionStage = command.executionStage();
        taskPlan.estimatedStartTimeMillis =
                command.estimatedStartTimeMillis();
        taskPlan.estimatedCompletionTimeMillis =
                command.estimatedCompletionTimeMillis();
        command.pathSteps().stream()
                .map(ReoptimizationStagedPathStep::create)
                .forEach(taskPlan::addPathStep);
        return taskPlan;
    }

    void assignStage(ReoptimizationPlanStage stage) {
        this.stage = stage;
    }

    private void addPathStep(ReoptimizationStagedPathStep pathStep) {
        pathSteps.add(pathStep);
        pathStep.assignTaskPlan(this);
    }
}
