package com.aivle.be.simulationrun.repository;

import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationRunRobotRepository extends JpaRepository<SimulationRunRobot, Long> {

    List<SimulationRunRobot> findAllBySimulationRun_IdOrderByRobot_Id(Long simulationRunId);

    boolean existsBySimulationRun_IdAndRobot_Id(Long simulationRunId, Long robotId);
}
