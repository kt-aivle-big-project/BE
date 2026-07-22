package com.aivle.be.optimization.entity;

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

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(
            mappedBy = "optimizationResult",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<RobotRouteResult> routes = new ArrayList<>();

    public static OptimizationResult create(
            String requestId,
            Long warehouseId,
            String status
    ) {
        OptimizationResult result = new OptimizationResult();
        result.requestId = requestId;
        result.warehouseId = warehouseId;
        result.status = status;
        return result;
    }

    public void addRoute(RobotRouteResult route) {
        routes.add(route);
        route.assignOptimizationResult(this);
    }
}