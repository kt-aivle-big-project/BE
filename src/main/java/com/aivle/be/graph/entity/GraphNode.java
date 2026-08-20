package com.aivle.be.graph.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Node("RouteNode")
@Getter
@NoArgsConstructor
public class GraphNode {

    @Id
    @Property("scope_id")
    private String scopeId;

    @Property("id")
    private String nodeId;

    @Property("warehouse_id")
    private String warehouseId;

    private String type;
    private Double x;
    private Double y;

    public GraphNode(
            String scopeId,
            String nodeId,
            String warehouseId,
            String type,
            Double x,
            Double y
    ) {
        this.scopeId = scopeId;
        this.nodeId = nodeId;
        this.warehouseId = warehouseId;
        this.type = type;
        this.x = x;
        this.y = y;
    }
}
