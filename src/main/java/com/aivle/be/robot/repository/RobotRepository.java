package com.aivle.be.robot.repository;

import com.aivle.be.robot.entity.Robot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RobotRepository
        extends JpaRepository<Robot, Long> {

    List<Robot> findAllByWarehouse_Id(Long warehouseId);
}