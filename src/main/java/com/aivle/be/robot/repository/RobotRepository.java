package com.aivle.be.robot.repository;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RobotRepository extends JpaRepository<Robot, Long> {

      List<Robot> findAllByWarehouse_Id(Long warehouseId);

      List<Robot> findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
              Long warehouseId,
              RobotAvailabilityStatus status
      );

      boolean existsByRobotSpec_Id(Long robotSpecId);

      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("select robot from Robot robot where robot.id = :robotId")
      Optional<Robot> findByIdForUpdate(@Param("robotId") Long robotId);

      @Lock(LockModeType.PESSIMISTIC_WRITE)
      @Query("""
              select robot
              from Robot robot
              where robot.id in :robotIds
              order by robot.id
              """)
      List<Robot> findAllByIdInForUpdateOrderById(
              @Param("robotIds") List<Long> robotIds
      );

      @Modifying(flushAutomatically = true, clearAutomatically = true)
      @Query("delete from Robot robot where robot.id = :robotId")
      void deleteByIdDirectly(@Param("robotId") Long robotId);
}
