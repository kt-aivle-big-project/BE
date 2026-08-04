package com.aivle.be.optimization.entity;

import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reoptimization_staged_path_steps",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reopt_stage_path_step",
                columnNames = {
                        "task_plan_id",
                        "segment_type",
                        "step_sequence"
                }
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReoptimizationStagedPathStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_plan_id", nullable = false)
    private ReoptimizationStagedTaskPlan taskPlan;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment_type", nullable = false, length = 20)
    private ReoptimizationPlanStageCommand.SegmentType segmentType;

    @Column(name = "segment_order", nullable = false)
    private Integer segmentOrder;

    @Column(name = "step_sequence", nullable = false)
    private Integer stepSequence;

    @Column(name = "node_id", nullable = false)
    private Long nodeId;

    @Column(name = "arrival_time_millis", nullable = false)
    private Long arrivalTimeMillis;

    @Column(name = "departure_time_millis", nullable = false)
    private Long departureTimeMillis;

    static ReoptimizationStagedPathStep create(
            ReoptimizationPlanStageCommand.PathStepCommand command
    ) {
        ReoptimizationStagedPathStep pathStep =
                new ReoptimizationStagedPathStep();
        pathStep.segmentType = command.segmentType();
        pathStep.segmentOrder = command.segmentType()
                == ReoptimizationPlanStageCommand.SegmentType.TO_START
                ? 0
                : 1;
        pathStep.stepSequence = command.stepSequence();
        pathStep.nodeId = command.nodeId();
        pathStep.arrivalTimeMillis = command.arrivalTimeMillis();
        pathStep.departureTimeMillis = command.departureTimeMillis();
        return pathStep;
    }

    void assignTaskPlan(ReoptimizationStagedTaskPlan taskPlan) {
        this.taskPlan = taskPlan;
    }
}
