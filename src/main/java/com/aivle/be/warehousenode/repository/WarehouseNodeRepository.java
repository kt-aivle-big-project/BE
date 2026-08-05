package com.aivle.be.warehousenode.repository;

import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WarehouseNodeRepository
        extends JpaRepository<WarehouseNode, Long> {

    List<WarehouseNode> findAllByWarehouse_Id(Long warehouseId);

    List<WarehouseNode> findAllByWarehouse_IdAndActiveTrue(Long warehouseId);

    Optional<WarehouseNode> findByWarehouse_IdAndNodeCode(Long warehouseId, String nodeCode);

    Optional<WarehouseNode> findByWarehouse_IdAndNodeCodeAndActiveTrue(
            Long warehouseId,
            String nodeCode
    );

    Optional<WarehouseNode> findByIdAndActiveTrue(Long nodeId);

    List<WarehouseNode> findAllByWarehouse_IdAndNodeType(Long warehouseId, NodeType nodeType);

    List<WarehouseNode> findAllByWarehouse_IdAndNodeTypeAndActiveTrue(
            Long warehouseId,
            NodeType nodeType
    );
}
