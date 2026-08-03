package com.aivle.be.optimization.repository;

import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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

    List<ReoptimizationPlanStage> findAllByStatusOrderById(
            ReoptimizationPlanStage.Status status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select stage
            from ReoptimizationPlanStage stage
            where stage.simulationRun.id = :simulationRunId
              and stage.replanId = :replanId
              and stage.snapshotVersion = :snapshotVersion
            """)
    Optional<ReoptimizationPlanStage> findExactForUpdate(
            @Param("simulationRunId") Long simulationRunId,
            @Param("replanId") String replanId,
            @Param("snapshotVersion") Long snapshotVersion
    );
}
