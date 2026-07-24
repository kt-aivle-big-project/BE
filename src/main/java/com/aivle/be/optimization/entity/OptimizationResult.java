package com.aivle.be.optimization.entity;

import com.aivle.be.optimization.domain.ReoptimizationReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "optimization_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OptimizationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String requestId;

    @Column(nullable = false)
    private Long warehouseId;

    @Column(nullable = false, length = 30)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private OptimizationType optimizationType;

    @Column
    private Long simulationRunId;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ReoptimizationReason reoptimizationReason;

    @Column
    private Long triggerRobotId;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(
            mappedBy = "optimizationResult",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<RobotRouteResult> routes = new ArrayList<>();

    @OneToMany(
            mappedBy = "optimizationResult",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<TaskAssignmentResult> taskAssignments =
            new ArrayList<>();

    public static OptimizationResult create(
            String requestId,
            Long warehouseId,
            String status
    ) {
        OptimizationResult result = new OptimizationResult();
        result.requestId = requestId;
        result.warehouseId = warehouseId;
        result.status = status;
        result.optimizationType = OptimizationType.INITIAL;
        return result;
    }

    public static OptimizationResult createReoptimization(
            String requestId,
            Long warehouseId,
            Long simulationRunId,
            String status,
            ReoptimizationReason reason,
            Long triggerRobotId,
            String description
    ) {
        OptimizationResult result = new OptimizationResult();
        result.requestId = requestId;
        result.warehouseId = warehouseId;
        result.simulationRunId = simulationRunId;
        result.status = status;
        result.optimizationType =
                OptimizationType.REOPTIMIZATION;
        result.reoptimizationReason = reason;
        result.triggerRobotId = triggerRobotId;
        result.description = description;
        return result;
    }

    public void addRoute(RobotRouteResult route) {
        routes.add(route);
        route.assignOptimizationResult(this);
    }

    public void addTaskAssignment(
            TaskAssignmentResult taskAssignment
    ) {
        taskAssignments.add(taskAssignment);
        taskAssignment.assignOptimizationResult(this);
    }

    public enum OptimizationType {
        INITIAL,
        REOPTIMIZATION
    }
}