package com.aivle.be.scenario.repository;

import com.aivle.be.scenario.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findAllByWarehouse_IdOrderByIdAsc(Long warehouseId);

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.warehouse.shared = true OR s.warehouse.user.id = :userId
            ORDER BY s.id
            """)
    List<Scenario> findAllVisibleToUser(@Param("userId") Long userId);

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.warehouse.shared = true
               OR s.warehouse.guestSessionId = :guestSessionId
            ORDER BY s.id
            """)
    List<Scenario> findAllVisibleToGuest(
            @Param("guestSessionId") String guestSessionId
    );

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.warehouse.id = :warehouseId
              AND (s.warehouse.shared = true OR s.warehouse.user.id = :userId)
            ORDER BY s.id
            """)
    List<Scenario> findAllVisibleToUserInWarehouse(
            @Param("warehouseId") Long warehouseId,
            @Param("userId") Long userId
    );

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.warehouse.id = :warehouseId
              AND (s.warehouse.shared = true
                   OR s.warehouse.guestSessionId = :guestSessionId)
            ORDER BY s.id
            """)
    List<Scenario> findAllVisibleToGuestInWarehouse(
            @Param("warehouseId") Long warehouseId,
            @Param("guestSessionId") String guestSessionId
    );

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.id = :scenarioId
              AND (s.warehouse.shared = true OR s.warehouse.user.id = :userId)
            """)
    Optional<Scenario> findVisibleToUser(
            @Param("scenarioId") Long scenarioId,
            @Param("userId") Long userId
    );

    @Query("""
            SELECT s FROM Scenario s
            WHERE s.id = :scenarioId
              AND (s.warehouse.shared = true
                   OR s.warehouse.guestSessionId = :guestSessionId)
            """)
    Optional<Scenario> findVisibleToGuest(
            @Param("scenarioId") Long scenarioId,
            @Param("guestSessionId") String guestSessionId
    );

    Optional<Scenario> findByIdAndWarehouse_User_Id(Long scenarioId, Long userId);

    Optional<Scenario> findByIdAndWarehouse_GuestSessionId(
            Long scenarioId,
            String guestSessionId
    );

    Optional<Scenario> findByWarehouse_IdAndScenarioCode(Long warehouseId, String scenarioCode);

    boolean existsByWarehouse_IdAndScenarioCode(Long warehouseId, String scenarioCode);
}
