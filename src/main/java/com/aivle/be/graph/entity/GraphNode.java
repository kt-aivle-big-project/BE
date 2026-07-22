package com.aivle.be.graph.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("WarehouseNode")
@Getter
@NoArgsConstructor
public class GraphNode {

    @Id
    private Long nodeId;

    private Long warehouseId;
    private String zoneId;
    private Double x;
    private Double y;

    public GraphNode(
            Long nodeId,
            Long warehouseId,
            String zoneId,
            Double x,
            Double y
    ) {
        this.nodeId = nodeId;
        this.warehouseId = warehouseId;
        this.zoneId = zoneId;
        this.x = x;
        this.y = y;
    }
}