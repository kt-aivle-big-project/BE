package com.aivle.be.warehouse.dto;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "창고 그래프(노드/간선) 전체")
public record WarehouseGraphResponse(

        @Schema(description = "창고 ID", example = "1")
        Long warehouseId,

        @Schema(description = "창고 이름", example = "대전 물류센터 A")
        String warehouseName,

        @Schema(
                description = "맵 버전. 노드·간선 구성이 바뀌면 값이 달라진다. "
                        + "AI 응답의 map_version 과 비교해 계획이 최신 맵 기준인지 확인한다.",
                example = "MAP-1-159-218-3f9a2b1c"
        )
        String mapVersion,

        @Schema(description = "노드 수", example = "159")
        int nodeCount,

        @Schema(description = "간선 수", example = "218")
        int edgeCount,

        List<GraphNode> nodes,

        List<GraphEdge> edges
) {

    @Schema(description = "그래프 노드")
    public record GraphNode(

            @Schema(description = "노드 코드", example = "R0_0")
            String code,

            @Schema(description = "노드 유형", example = "ROUTE")
            String type,

            @Schema(description = "구역 코드", example = "MOVING_ZONE")
            String zone,

            @Schema(description = "X 좌표", example = "4.1")
            Double x,

            @Schema(description = "Y 좌표", example = "0.72")
            Double y,

            Boolean serviceOnly,

            Boolean transitAllowed,

            Boolean holdingAllowed,

            Integer nodeCapacity,

            String resourceType,

            String resourceCode,

            String side,

            @Schema(description = "LARO RouteNode 추가 속성")
            Map<String, Object> routeAttributes
    ) {

        public static GraphNode from(WarehouseNode node) {
            return new GraphNode(
                    node.getNodeCode(),
                    node.getNodeType() == null ? null : node.getNodeType().name(),
                    node.getZoneId(),
                    node.getX(),
                    node.getY(),
                    node.getServiceOnly(),
                    node.getTransitAllowed(),
                    node.getHoldingAllowed(),
                    node.getNodeCapacity(),
                    node.getResourceType(),
                    node.getResourceCode(),
                    node.getSide(),
                    node.getRouteAttributes()
            );
        }
    }

    @Schema(description = "그래프 간선")
    public record GraphEdge(

            @Schema(description = "간선 코드", example = "H0_0")
            String code,

            @Schema(description = "시작 노드 코드", example = "R0_0")
            String from,

            @Schema(description = "도착 노드 코드", example = "R0_1")
            String to,

            @Schema(description = "거리", example = "0.9")
            Double distance,

            @Schema(
                    description = "통행 방향. BOTH(양방향) / A_TO_B / B_TO_A",
                    example = "BOTH"
            )
            String direction,

            String edgeType,

            Double speedLimitMps,

            Long nominalTravelTimeMs,

            Double cost,

            String physicalResourceCode,

            Boolean serviceOnly,

            Boolean mobileRobotTraversable,

            @Schema(description = "LARO TRAVERSES 추가 속성")
            Map<String, Object> routeAttributes
    ) {

        public static GraphEdge from(WarehouseEdge edge) {
            return new GraphEdge(
                    edge.getEdgeCode(),
                    edge.getFromNode().getNodeCode(),
                    edge.getToNode().getNodeCode(),
                    edge.getDistance(),
                    edge.getDirectionType() == null
                            ? null
                            : edge.getDirectionType().name(),
                    edge.getEdgeType(),
                    edge.getSpeedLimitMps(),
                    edge.getNominalTravelTimeMs(),
                    edge.getCost(),
                    edge.getPhysicalResourceCode(),
                    edge.getServiceOnly(),
                    edge.getMobileRobotTraversable(),
                    edge.getRouteAttributes()
            );
        }
    }
}
