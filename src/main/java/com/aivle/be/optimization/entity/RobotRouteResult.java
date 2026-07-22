package com.aivle.be.optimization.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "robot_route_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RobotRouteResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "optimization_result_id", nullable = false)
    private OptimizationResult optimizationResult;

    @Column(nullable = false)
    private Long robotId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String nodePath;

    @Column(nullable = false)
    private Double totalDistance;

    @Column(nullable = false)
    private Double estimatedTime;

    public static RobotRouteResult create(
            Long robotId,
            String nodePath,
            Double totalDistance,
            Double estimatedTime
    ) {
        RobotRouteResult route = new RobotRouteResult();
        route.robotId = robotId;
        route.nodePath = nodePath;
        route.totalDistance = totalDistance;
        route.estimatedTime = estimatedTime;
        return route;
    }

    void assignOptimizationResult(
            OptimizationResult optimizationResult
    ) {
        this.optimizationResult = optimizationResult;
    }
}