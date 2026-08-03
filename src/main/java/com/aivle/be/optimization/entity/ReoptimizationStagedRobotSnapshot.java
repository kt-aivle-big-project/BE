package com.aivle.be.optimization.entity;

import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
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
        name = "reoptimization_staged_robot_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reopt_stage_robot_snapshot",
                columnNames = {"stage_id", "robot_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReoptimizationStagedRobotSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stage_id", nullable = false)
    private ReoptimizationPlanStage stage;

    @Column(name = "robot_id", nullable = false)
    private Long robotId;

    @Column(name = "current_node_id")
    private Long currentNodeId;

    @Column(name = "battery_level")
    private Double batteryLevel;

    @Column(name = "runtime_status", nullable = false, length = 30)
    private String status;

    @Column(name = "current_task_id")
    private Long currentTaskId;

    @Column(name = "runtime_phase", nullable = false, length = 30)
    private String runtimePhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "remaining_stage", nullable = false, length = 30)
    private ReoptimizationOptimizationRequest.RemainingStage remainingStage;

    static ReoptimizationStagedRobotSnapshot create(
            ReoptimizationPlanStageCommand.RobotSnapshotCommand command
    ) {
        ReoptimizationStagedRobotSnapshot snapshot =
                new ReoptimizationStagedRobotSnapshot();
        snapshot.robotId = command.robotId();
        snapshot.currentNodeId = command.currentNodeId();
        snapshot.batteryLevel = command.batteryLevel();
        snapshot.status = command.status();
        snapshot.currentTaskId = command.currentTaskId();
        snapshot.runtimePhase = command.runtimePhase();
        snapshot.remainingStage = command.remainingStage();
        return snapshot;
    }

    void assignStage(ReoptimizationPlanStage stage) {
        this.stage = stage;
    }
}
