package com.aivle.be.warehousenode.dto;

import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

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
    private Boolean serviceOnly;
    private Boolean transitAllowed;
    private Boolean holdingAllowed;
    private Integer nodeCapacity;
    private String resourceType;
    private String resourceCode;
    private String side;
    private Boolean active;
    private Map<String, Object> routeAttributes;

    public static WarehouseNodeResponse from(WarehouseNode node) {
        return WarehouseNodeResponse.builder()
                .id(node.getId())
                .warehouseId(node.getWarehouse().getId())
                .zoneId(node.getZoneId())
                .nodeCode(node.getNodeCode())
                .nodeType(node.getNodeType())
                .x(node.getX())
                .y(node.getY())
                .serviceOnly(node.getServiceOnly())
                .transitAllowed(node.getTransitAllowed())
                .holdingAllowed(node.getHoldingAllowed())
                .nodeCapacity(node.getNodeCapacity())
                .resourceType(node.getResourceType())
                .resourceCode(node.getResourceCode())
                .side(node.getSide())
                .active(node.isActive())
                .routeAttributes(node.getRouteAttributes())
                .build();
    }
}
