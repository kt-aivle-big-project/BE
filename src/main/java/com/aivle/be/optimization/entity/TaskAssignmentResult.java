package com.aivle.be.optimization.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "task_assignment_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskAssignmentResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "optimization_result_id",
            nullable = false
    )
    private OptimizationResult optimizationResult;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "previous_robot_id")
    private Long previousRobotId;

    @Column(name = "assigned_robot_id", nullable = false)
    private Long assignedRobotId;

    public static TaskAssignmentResult create(
            Long taskId,
            Long previousRobotId,
            Long assignedRobotId
    ) {
        TaskAssignmentResult result =
                new TaskAssignmentResult();

        result.taskId = taskId;
        result.previousRobotId = previousRobotId;
        result.assignedRobotId = assignedRobotId;

        return result;
    }

    void assignOptimizationResult(
            OptimizationResult optimizationResult
    ) {
        this.optimizationResult = optimizationResult;
    }
}