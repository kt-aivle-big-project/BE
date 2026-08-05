package com.aivle.be.warehouseedge.dto;

import com.aivle.be.warehouseedge.entity.WarehouseEdge.DirectionType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@NoArgsConstructor
@Setter
public class WarehouseEdgeUpdateRequest {

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
}
