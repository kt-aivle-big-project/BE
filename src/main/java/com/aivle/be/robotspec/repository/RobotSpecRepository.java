package com.aivle.be.robotspec.repository;

import com.aivle.be.robotspec.entity.RobotSpec;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RobotSpecRepository
        extends JpaRepository<RobotSpec, Long> {

    boolean existsByRobotCode(String robotCode);

    boolean existsByRobotCodeAndIdNot(String robotCode, Long id);
}
