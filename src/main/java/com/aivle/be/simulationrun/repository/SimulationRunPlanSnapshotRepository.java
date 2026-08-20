package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRunPlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SimulationRunPlanSnapshotRepository
        extends JpaRepository<SimulationRunPlanSnapshot, Long> {

    Optional<SimulationRunPlanSnapshot> findBySimulationRunIdAndCycleMinute(
            Long simulationRunId,
            long cycleMinute
    );

    List<SimulationRunPlanSnapshot> findAllBySimulationRunIdOrderByCycleMinuteAsc(
            Long simulationRunId
    );

    Optional<SimulationRunPlanSnapshot> findFirstBySimulationRunIdOrderByCycleMinuteDesc(
            Long simulationRunId
    );

    void deleteAllBySimulationRunId(Long simulationRunId);
}
