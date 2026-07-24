package com.aivle.be.warehouseitem.repository;

import com.aivle.be.warehouseitem.entity.WarehouseItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseItemRepository extends JpaRepository<WarehouseItem, Long> {

    List<WarehouseItem> findAllByWarehouse_Id(Long warehouseId);
}