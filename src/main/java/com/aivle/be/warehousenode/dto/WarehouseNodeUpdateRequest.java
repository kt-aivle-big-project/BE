package com.aivle.be.warehousenode.dto;

import com.aivle.be.warehousenode.domain.NodeType;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor
public class WarehouseNodeUpdateRequest {

    private String zoneId;
    private Double x;
    private Double y;
    private String nodeCode;
    private NodeType nodeType;
    private Boolean serviceOnly;
    private Boolean transitAllowed;
    private Boolean holdingAllowed;
    private Integer nodeCapacity;
    private String resourceType;
    private String resourceCode;
    private String side;
    private Map<String, Object> routeAttributes;
}
