package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRunPlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SimulationRunPlanSnapshotRepository
        extends JpaRepository<SimulationRunPlanSnapshot, Long> {

    Optional<SimulationRunPlanSnapshot> findBySimulationRunIdAndCycleMinute(
            Long simulationRunId,
            long cycleMinute
    );

    boolean existsBySimulationRunId(Long simulationRunId);
}
