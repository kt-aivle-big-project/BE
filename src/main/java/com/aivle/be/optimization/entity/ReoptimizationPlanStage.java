package com.aivle.be.optimization.entity;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.simulationrun.entity.SimulationRun;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "reoptimization_plan_stages",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reopt_stage_run_replan_snapshot",
                        columnNames = {
                                "simulation_run_id",
                                "replan_id",
                                "snapshot_version"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_reopt_stage_replan_id",
                        columnNames = "replan_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReoptimizationPlanStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "simulation_run_id", nullable = false)
    private SimulationRun simulationRun;

    @Column(name = "replan_id", nullable = false, length = 36)
    private String replanId;

    @Column(name = "snapshot_version", nullable = false)
    private Long snapshotVersion;

    @Column(name = "request_id")
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "simulation_clock_millis", nullable = false)
    private Long simulationClockMillis;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 50)
    private ReoptimizationReason reason;

    @Column(name = "trigger_robot_id")
    private Long triggerRobotId;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "response_message", columnDefinition = "text")
    private String responseMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "reoptimization_stage_blocked_edges",
            joinColumns = @JoinColumn(
                    name = "stage_id",
                    nullable = false
            )
    )
    @OrderColumn(name = "edge_sequence")
    @Column(name = "edge_id", nullable = false)
    private List<Long> blockedEdgeIds = new ArrayList<>();

    @OneToMany(
            mappedBy = "stage",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("robotId ASC, sequence ASC")
    private List<ReoptimizationStagedTaskPlan> taskPlans =
            new ArrayList<>();

    public static ReoptimizationPlanStage create(
            SimulationRun simulationRun,
            ReoptimizationPlanStageCommand command
    ) {
        ReoptimizationPlanStage stage = new ReoptimizationPlanStage();
        stage.simulationRun = simulationRun;
        stage.replanId = command.replanId();
        stage.snapshotVersion = command.snapshotVersion();
        stage.requestId = command.requestId();
        stage.status = Status.STAGED;
        stage.simulationClockMillis = command.simulationClockMillis();
        stage.reason = command.reason();
        stage.triggerRobotId = command.triggerRobotId();
        stage.description = command.description();
        stage.responseMessage = command.responseMessage();
        stage.blockedEdgeIds.addAll(command.blockedEdgeIds());

        command.taskPlans().stream()
                .map(ReoptimizationStagedTaskPlan::create)
                .forEach(stage::addTaskPlan);
        return stage;
    }

    private void addTaskPlan(ReoptimizationStagedTaskPlan taskPlan) {
        taskPlans.add(taskPlan);
        taskPlan.assignStage(this);
    }

    public enum Status {
        STAGED,
        APPLIED,
        REJECTED
    }
}
