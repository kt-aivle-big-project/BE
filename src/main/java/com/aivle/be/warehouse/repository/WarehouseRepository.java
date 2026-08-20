package com.aivle.be.warehouse.repository;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    @Query("""
            SELECT w FROM Warehouse w
            WHERE w.shared = true
               OR w.user.id = :userId
            ORDER BY w.id
            """)
    List<Warehouse> findVisibleTo(@Param("userId") Long userId);

    @Query("SELECT w FROM Warehouse w WHERE w.shared = true ORDER BY w.id")
    List<Warehouse> findShared();

    Optional<Warehouse> findByIdAndSharedTrue(Long warehouseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select warehouse from Warehouse warehouse where warehouse.id = :warehouseId")
    Optional<Warehouse> findByIdForUpdate(
            @Param("warehouseId") Long warehouseId
    );

    Optional<Warehouse> findByUser_IdAndSourceTemplate_Id(
            Long userId,
            Long sourceTemplateId
    );

    boolean existsByUser_IdAndSourceTemplate_Id(
            Long userId,
            Long sourceTemplateId
    );

    Optional<Warehouse> findByGuestSessionIdAndSourceTemplate_Id(
            String guestSessionId,
            Long sourceTemplateId
    );

    boolean existsByGuestSessionIdAndSourceTemplate_Id(
            String guestSessionId,
            Long sourceTemplateId
    );

    boolean existsByUser_IdAndSharedTrue(Long userId);
}
