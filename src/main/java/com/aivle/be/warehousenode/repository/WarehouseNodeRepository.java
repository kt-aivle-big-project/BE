package com.aivle.be.warehousenode.repository;

import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseNodeRepository
        extends JpaRepository<WarehouseNode, Long> {

    List<WarehouseNode> findAllByWarehouse_Id(Long warehouseId);
}