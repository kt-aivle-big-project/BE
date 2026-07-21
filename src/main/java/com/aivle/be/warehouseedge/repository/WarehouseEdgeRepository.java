package com.aivle.be.warehouseedge.repository;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WarehouseEdgeRepository
        extends JpaRepository<WarehouseEdge, Long> {

    List<WarehouseEdge> findAllByFromNode_Warehouse_Id(Long warehouseId);
}