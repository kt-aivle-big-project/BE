package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SimulationRunRepository extends JpaRepository<SimulationRun, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from SimulationRun run where run.id = :id")
    Optional<SimulationRun> findByIdForUpdate(@Param("id") Long id);

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
