package com.aivle.be.graph.service;

import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GraphSyncService {

    private final WarehouseNodeRepository warehouseNodeRepository;
    private final Neo4jClient neo4jClient;
    private final WarehouseEdgeRepository warehouseEdgeRepository;

    public int syncNodes(Long warehouseId) {
        List<WarehouseNode> nodes =
                warehouseNodeRepository.findAllByWarehouse_Id(warehouseId);

        List<Map<String, Object>> nodeParameters = nodes.stream()
                .map(node -> {
                    Map<String, Object> values = new HashMap<>();
                    values.put("nodeId", node.getId());
                    values.put("warehouseId", node.getWarehouse().getId());
                    values.put("zoneId", node.getZoneId());
                    values.put("x", node.getX());
                    values.put("y", node.getY());
                    return values;
                })
                .toList();

        neo4jClient.query("""
                UNWIND $nodes AS node
                MERGE (n:WarehouseNode {nodeId: node.nodeId})
                SET n.warehouseId = node.warehouseId,
                    n.zoneId = node.zoneId,
                    n.x = node.x,
                    n.y = node.y
                """)
                .bind(nodeParameters)
                .to("nodes")
                .run();

        return nodes.size();
    }
    public int syncEdges(Long warehouseId) {
        List<WarehouseEdge> edges =
                warehouseEdgeRepository.findAllByFromNode_Warehouse_Id(warehouseId);

        for (WarehouseEdge edge : edges) {
            Long fromNodeId = edge.getFromNode().getId();
            Long toNodeId = edge.getToNode().getId();

            switch (edge.getDirectionType()) {
                case BOTH -> {
                    createRelationship(
                            edge.getId(),
                            fromNodeId,
                            toNodeId,
                            edge.getDistance(),
                            "BOTH"
                    );

                    createRelationship(
                            edge.getId(),
                            toNodeId,
                            fromNodeId,
                            edge.getDistance(),
                            "BOTH"
                    );
                }

                case A_TO_B -> createRelationship(
                        edge.getId(),
                        fromNodeId,
                        toNodeId,
                        edge.getDistance(),
                        "A_TO_B"
                );

                case B_TO_A -> createRelationship(
                        edge.getId(),
                        toNodeId,
                        fromNodeId,
                        edge.getDistance(),
                        "B_TO_A"
                );
            }
        }

        return edges.size();
    }

    private void createRelationship(
            Long edgeId,
            Long fromNodeId,
            Long toNodeId,
            Double distance,
            String directionType
    ) {
        neo4jClient.query("""
            MATCH (from:WarehouseNode {nodeId: $fromNodeId})
            MATCH (to:WarehouseNode {nodeId: $toNodeId})
            MERGE (from)-[r:CONNECTED_TO {
                edgeId: $edgeId,
                directionType: $directionType
            }]->(to)
            SET r.distance = $distance
            """)
                .bind(fromNodeId).to("fromNodeId")
                .bind(toNodeId).to("toNodeId")
                .bind(edgeId).to("edgeId")
                .bind(directionType).to("directionType")
                .bind(distance).to("distance")
                .run();
    }
    public Map<String, Integer> syncWarehouseGraph(Long warehouseId) {
        int nodeCount = syncNodes(warehouseId);
        int edgeCount = syncEdges(warehouseId);

        return Map.of(
                "syncedNodeCount", nodeCount,
                "syncedEdgeCount", edgeCount
        );
    }
}