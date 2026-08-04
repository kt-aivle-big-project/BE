package com.aivle.be.warehouseedge.dto;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.entity.WarehouseEdge.DirectionType;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class WarehouseEdgeResponse {

    private Long id;
    private Long fromNodeId;
    private Long toNodeId;
    private Double distance;
    private DirectionType directionType;
    private String edgeCode;
    private String edgeType;
    private Double speedLimitMps;
    private Long nominalTravelTimeMs;
    private Double cost;
    private String physicalResourceCode;
    private Boolean serviceOnly;
    private Boolean mobileRobotTraversable;
    private Map<String, Object> routeAttributes;

    public static WarehouseEdgeResponse from(WarehouseEdge edge) {
        return WarehouseEdgeResponse.builder()
                .id(edge.getId())
                .fromNodeId(edge.getFromNode().getId())
                .toNodeId(edge.getToNode().getId())
                .distance(edge.getDistance())
                .directionType(edge.getDirectionType())
                .edgeCode(edge.getEdgeCode())
                .edgeType(edge.getEdgeType())
                .speedLimitMps(edge.getSpeedLimitMps())
                .nominalTravelTimeMs(edge.getNominalTravelTimeMs())
                .cost(edge.getCost())
                .physicalResourceCode(edge.getPhysicalResourceCode())
                .serviceOnly(edge.getServiceOnly())
                .mobileRobotTraversable(edge.getMobileRobotTraversable())
                .routeAttributes(edge.getRouteAttributes())
                .build();
    }
}
