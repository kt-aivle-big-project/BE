package com.aivle.be.optimization.repository;

import com.aivle.be.optimization.entity.OptimizationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OptimizationResultRepository
        extends JpaRepository<OptimizationResult, Long> {

    Optional<OptimizationResult> findByRequestId(String requestId);
}