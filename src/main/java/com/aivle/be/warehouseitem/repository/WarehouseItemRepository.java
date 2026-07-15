package com.aivle.be.warehouseitem.repository;

import com.aivle.be.warehouseitem.entity.WarehouseItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseItemRepository extends JpaRepository<WarehouseItem, Long> {
}