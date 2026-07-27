package com.aivle.be.warehousenode.dto;

import com.aivle.be.warehousenode.domain.NodeType;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WarehouseNodeUpdateRequest {

    private String zoneId;
    private Double x;
    private Double y;
    private String nodeCode;
    private NodeType nodeType;
}
