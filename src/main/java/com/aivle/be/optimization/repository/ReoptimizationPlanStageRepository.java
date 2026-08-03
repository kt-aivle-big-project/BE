package com.aivle.be.optimization.repository;

import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReoptimizationPlanStageRepository
        extends JpaRepository<ReoptimizationPlanStage, Long> {

    Optional<ReoptimizationPlanStage> findByReplanId(String replanId);

    Optional<ReoptimizationPlanStage>
    findBySimulationRun_IdAndReplanId(
            Long simulationRunId,
            String replanId
    );

    Optional<ReoptimizationPlanStage>
    findFirstBySimulationRun_IdAndStatusOrderByCreatedAtDescIdDesc(
            Long simulationRunId,
            ReoptimizationPlanStage.Status status
    );
}
