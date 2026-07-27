package com.aivle.be.warehousenode.dto;

import com.aivle.be.warehousenode.domain.NodeType;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WarehouseNodeCreateRequest {

    private Long warehouseId;
    private String zoneId;
    private Double x;
    private Double y;

    // 프론트 그래프 노드 식별자 ("R0_0")
    private String nodeCode;

    // ROUTE / RACK_STORAGE / INBOUND / OUTBOUND / CHARGING_SLOT / ROUTE_CHARGE_JUNCTION
    private NodeType nodeType;
}
