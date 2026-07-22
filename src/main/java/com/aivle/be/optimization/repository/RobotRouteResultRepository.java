package com.aivle.be.optimization.repository;

import com.aivle.be.optimization.entity.RobotRouteResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RobotRouteResultRepository
        extends JpaRepository<RobotRouteResult, Long> {
}