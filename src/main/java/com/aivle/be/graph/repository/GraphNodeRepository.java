package com.aivle.be.graph.repository;

import com.aivle.be.graph.entity.GraphNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;

public interface GraphNodeRepository
        extends Neo4jRepository<GraphNode, Long> {

    List<GraphNode> findAllByWarehouseId(Long warehouseId);
}