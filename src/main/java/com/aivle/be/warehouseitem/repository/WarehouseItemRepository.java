package com.aivle.be.warehouseitem.repository;

import com.aivle.be.warehouseitem.entity.WarehouseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface WarehouseItemRepository extends JpaRepository<WarehouseItem, Long> {

    List<WarehouseItem> findAllByWarehouse_Id(Long warehouseId);

    List<WarehouseItem> findAllByStorageLocation_IdOrderByRackLevelAsc(Long storageLocationId);

    boolean existsByStorageLocation_IdAndRackLevel(Long storageLocationId, Integer rackLevel);

    boolean existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(
            Long storageLocationId,
            Integer rackLevel,
            Integer quantity
    );

    boolean existsByStorageLocation_IdAndRackLevelAndIdNot(
            Long storageLocationId,
            Integer rackLevel,
            Long warehouseItemId
    );

    Optional<WarehouseItem> findByStorageLocation_IdAndRackLevel(
            Long storageLocationId,
            Integer rackLevel
    );

    Optional<WarehouseItem> findFirstByWarehouse_IdAndProduct_IdAndNode_Id(
            Long warehouseId,
            Long productId,
            Long nodeId
    );
}
