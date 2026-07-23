package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SimulationRunRobotRepository extends JpaRepository<SimulationRunRobot, Long> {

    List<SimulationRunRobot> findAllBySimulationRun_IdOrderByRobot_Id(Long simulationRunId);

    boolean existsBySimulationRun_IdAndRobot_Id(Long simulationRunId, Long robotId);

    boolean existsByRobot_IdAndSimulationRun_StatusIn(
            Long robotId,
            Collection<SimulationRunStatus> statuses
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SimulationRunRobot participant where participant.robot.id = :robotId")
    void deleteAllByRobotId(@Param("robotId") Long robotId);
}
