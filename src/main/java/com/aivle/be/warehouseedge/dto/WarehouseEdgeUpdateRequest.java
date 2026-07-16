package com.aivle.be.warehouseedge.dto;

import com.aivle.be.warehouseedge.entity.WarehouseEdge.DirectionType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@NoArgsConstructor
@Setter
public class WarehouseEdgeUpdateRequest {

    private Long fromNodeId;
    private Long toNodeId;
    private Double distance;
    private DirectionType directionType;
}