package com.aivle.be.warehousezone.repository;

import com.aivle.be.warehousezone.entity.WarehouseZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseZoneRepository
        extends JpaRepository<WarehouseZone, Long> {

    List<WarehouseZone> findAllByWarehouse_Id(Long warehouseId);

    java.util.Optional<WarehouseZone> findByWarehouse_IdAndName(Long warehouseId, String name);
}
