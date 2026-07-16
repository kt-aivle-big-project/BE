package com.aivle.be.warehousezone.repository;

import com.aivle.be.warehousezone.entity.WarehouseZone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseZoneRepository
        extends JpaRepository<WarehouseZone, Long> {
}