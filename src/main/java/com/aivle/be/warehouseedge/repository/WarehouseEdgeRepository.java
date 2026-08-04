package com.aivle.be.warehouseedge.repository;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WarehouseEdgeRepository
        extends JpaRepository<WarehouseEdge, Long> {

    List<WarehouseEdge> findAllByFromNode_Warehouse_Id(Long warehouseId);

    List<WarehouseEdge> findAllByFromNode_IdOrToNode_Id(Long fromNodeId, Long toNodeId);

    @Query("""
            select edge
            from WarehouseEdge edge
            where edge.fromNode.warehouse.id = :warehouseId
              and edge.fromNode.active = true
              and edge.toNode.active = true
            """)
    List<WarehouseEdge> findAllActiveByWarehouseId(@Param("warehouseId") Long warehouseId);
}
