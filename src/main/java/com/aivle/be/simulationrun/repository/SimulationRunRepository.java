package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    boolean existsByWarehouse_IdAndStatusInAndIdNot(
            Long warehouseId,
            Collection<SimulationRunStatus> statuses,
            Long simulationRunId
    );
}
