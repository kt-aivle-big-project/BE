package com.aivle.be.robotstate.repository;

import com.aivle.be.robotstate.domain.RobotState;

import java.util.List;
import java.util.Optional;

public interface RobotStateStore {
    RobotState save(RobotState state);
    Optional<RobotState> findByRobotId(Long robotId);
    List<RobotState> findAllByWarehouseId(Long warehouseId);
}
