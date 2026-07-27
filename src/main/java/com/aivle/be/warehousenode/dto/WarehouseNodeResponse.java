package com.aivle.be.warehousenode.dto;

import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WarehouseNodeResponse {

    private Long id;
    private Long warehouseId;
    private String zoneId;
    private String nodeCode;
    private NodeType nodeType;
    private Double x;
    private Double y;

    public static WarehouseNodeResponse from(WarehouseNode node) {
        return WarehouseNodeResponse.builder()
                .id(node.getId())
                .warehouseId(node.getWarehouse().getId())
                .zoneId(node.getZoneId())
                .nodeCode(node.getNodeCode())
                .nodeType(node.getNodeType())
                .x(node.getX())
                .y(node.getY())
                .build();
    }
}
