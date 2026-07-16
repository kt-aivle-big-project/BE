package com.aivle.be.warehouseedge.dto;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.entity.WarehouseEdge.DirectionType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WarehouseEdgeResponse {

    private Long id;
    private Long fromNodeId;
    private Long toNodeId;
    private Double distance;
    private DirectionType directionType;

    public static WarehouseEdgeResponse from(WarehouseEdge edge) {
        return WarehouseEdgeResponse.builder()
                .id(edge.getId())
                .fromNodeId(edge.getFromNode().getId())
                .toNodeId(edge.getToNode().getId())
                .distance(edge.getDistance())
                .directionType(edge.getDirectionType())
                .build();
    }
}