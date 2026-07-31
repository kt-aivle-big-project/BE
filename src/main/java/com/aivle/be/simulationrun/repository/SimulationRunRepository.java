package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    boolean existsByWarehouse_IdAndGuestSessionIdIsNullAndStatusInAndIdNot(
            Long warehouseId,
            Collection<SimulationRunStatus> statuses,
            Long simulationRunId
    );

    boolean existsByGuestSessionIdAndStatusInAndIdNot(
            String guestSessionId,
            Collection<SimulationRunStatus> statuses,
            Long simulationRunId
    );

    List<SimulationRun> findAllByWarehouse_IdAndStatusIn(
            Long warehouseId,
            Collection<SimulationRunStatus> statuses
    );

    /**
     * 특정 사용자가 실행한 시뮬레이션 목록 (최신순).
     */
    List<SimulationRun> findAllByUser_IdOrderByIdDesc(Long userId);

    List<SimulationRun> findAllByGuestSessionIdOrderByIdDesc(String guestSessionId);
}
