package com.aivle.be.event.repository;

import com.aivle.be.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Modifying(flushAutomatically = true)
    @Query("update Event event set event.robot = null where event.robot.id = :robotId")
    void clearRobotReference(@Param("robotId") Long robotId);

    /* =========================================================
       운영 대시보드 집계
    ========================================================= */

    List<Event> findAllByOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            LocalDateTime from,
            LocalDateTime to
    );

    List<Event> findAllByWarehouse_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
            Long warehouseId,
            LocalDateTime from,
            LocalDateTime to
    );
}
