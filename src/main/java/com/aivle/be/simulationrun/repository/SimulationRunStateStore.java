package com.aivle.be.simulationrun.repository;

import com.aivle.be.robotstate.domain.RobotState;

import java.util.List;
import java.util.Optional;

public interface SimulationRunStateStore {

    RobotState save(Long simulationRunId, RobotState state);

    Optional<RobotState> findByRobotId(Long simulationRunId, Long robotId);

    List<RobotState> findAll(Long simulationRunId);

    void deleteAll(Long simulationRunId);
}
