package com.aivle.be.graph.service;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehouse.service.WarehouseFacilitySyncService;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GraphSyncService {

    private final WarehouseNodeRepository warehouseNodeRepository;
    private final Neo4jClient neo4jClient;
    private final Driver neo4jDriver;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final WarehouseFacilitySyncService warehouseFacilitySyncService;

    public int syncNodes(Long warehouseId) {
        String warehouseCode = warehouseCode(warehouseId);
        List<WarehouseNode> warehouseNodes = warehouseNodeRepository
                .findAllByWarehouse_IdAndActiveTrue(warehouseId);
        Map<Long, List<String>> rackIdsByRouteNode = rackIdsByRouteNode(
                warehouseNodes,
                warehouseEdgeRepository.findAllActiveByWarehouseId(warehouseId)
        );
        List<Map<String, Object>> nodes = warehouseNodes
                .stream()
                .filter(this::isRouteNode)
                .map(node -> nodeProperties(
                        node,
                        warehouseCode,
                        rackIdsByRouteNode.getOrDefault(node.getId(), List.of())
                ))
                .toList();

        ensureSchema();
        neo4jClient.query("""
                MATCH (n:RouteNode {warehouse_id: $warehouseId})
                DETACH DELETE n
                """)
                .bind(warehouseCode).to("warehouseId")
                .run();

        if (!nodes.isEmpty()) {
            neo4jClient.query("""
                    UNWIND $nodes AS node
                    MERGE (n:RouteNode {scope_id: node.scope_id})
                    SET n = node
                    REMOVE n:ServiceAccess:RackAccess:InboundHandoffAccess:OutboundStationAccess:EmptyToteBufferAccess
                    FOREACH (_ IN CASE WHEN node.type IN [
                        'rack_access',
                        'inbound_handoff_access',
                        'outbound_station_access',
                        'empty_tote_buffer_access'
                    ] THEN [1] ELSE [] END | SET n:ServiceAccess)
                    FOREACH (_ IN CASE WHEN node.type = 'rack_access' THEN [1] ELSE [] END | SET n:RackAccess)
                    FOREACH (_ IN CASE WHEN node.type = 'inbound_handoff_access' THEN [1] ELSE [] END | SET n:InboundHandoffAccess)
                    FOREACH (_ IN CASE WHEN node.type = 'outbound_station_access' THEN [1] ELSE [] END | SET n:OutboundStationAccess)
                    FOREACH (_ IN CASE WHEN node.type = 'empty_tote_buffer_access' THEN [1] ELSE [] END | SET n:EmptyToteBufferAccess)
                    """)
                    .bind(nodes).to("nodes")
                    .run();
        }
        return nodes.size();
    }

    public int syncEdges(Long warehouseId) {
        String warehouseCode = warehouseCode(warehouseId);
        Set<String> routeNodeCodes = warehouseNodeRepository
                .findAllByWarehouse_IdAndActiveTrue(warehouseId)
                .stream()
                .filter(this::isRouteNode)
                .map(WarehouseNode::getNodeCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<Map<String, Object>> edges = new ArrayList<>();
        for (WarehouseEdge edge : warehouseEdgeRepository.findAllActiveByWarehouseId(warehouseId)) {
            if (!isTraversable(edge)) {
                continue;
            }
            String source = edge.getFromNode().getNodeCode();
            String target = edge.getToNode().getNodeCode();
            if (!routeNodeCodes.contains(source) || !routeNodeCodes.contains(target)) {
                continue;
            }
            String code = edgeCode(edge);
            switch (edge.getDirectionType()) {
                case BOTH -> {
                    edges.add(edgeRow(edge, warehouseCode, code + ":F", source, target));
                    edges.add(edgeRow(edge, warehouseCode, code + ":R", target, source));
                }
                case A_TO_B -> edges.add(edgeRow(edge, warehouseCode, code, source, target));
                case B_TO_A -> edges.add(edgeRow(edge, warehouseCode, code, target, source));
            }
        }

        neo4jClient.query("""
                MATCH (:RouteNode {warehouse_id: $warehouseId})
                      -[r:TRAVERSES]->
                      (:RouteNode {warehouse_id: $warehouseId})
                DELETE r
                """)
                .bind(warehouseCode).to("warehouseId")
                .run();

        if (!edges.isEmpty()) {
            neo4jClient.query("""
                    UNWIND $edges AS edge
                    MATCH (a:RouteNode {scope_id: edge.source_scope_id})
                    MATCH (b:RouteNode {scope_id: edge.target_scope_id})
                    MERGE (a)-[r:TRAVERSES {scope_id: edge.properties.scope_id}]->(b)
                    SET r = edge.properties
                    """)
                    .bind(edges).to("edges")
                    .run();
        }
        return edges.size();
    }

    public Map<String, Integer> syncWarehouseGraph(Long warehouseId) {
        warehouseFacilitySyncService.synchronizeOutboundFacilities(
                warehouseId,
                warehouseNodeRepository.findAllByWarehouse_IdAndActiveTrue(warehouseId),
                warehouseEdgeRepository.findAllActiveByWarehouseId(warehouseId)
        );
        int nodeCount = syncNodes(warehouseId);
        int edgeCount = syncEdges(warehouseId);
        return Map.of(
                "syncedNodeCount", nodeCount,
                "syncedEdgeCount", edgeCount
        );
    }

    private void ensureSchema() {
        // Neo4j does not allow schema changes and data writes in the same
        // transaction. Use a dedicated driver session so these auto-commit
        try (Session session = neo4jDriver.session()) {
            session.run("""
                    CREATE CONSTRAINT route_node_scope_id IF NOT EXISTS
                    FOR (n:RouteNode) REQUIRE n.scope_id IS UNIQUE
                    """).consume();
            session.run("""
                    CREATE INDEX route_node_warehouse IF NOT EXISTS
                    FOR (n:RouteNode) ON (n.warehouse_id)
                    """).consume();
            session.run("""
                    CREATE INDEX route_node_type IF NOT EXISTS
                    FOR (n:RouteNode) ON (n.type)
                    """).consume();
        }
    }

    private boolean isRouteNode(WarehouseNode node) {
        if (node.getNodeCode() == null || node.getNodeCode().isBlank()) {
            return false;
        }
        if (node.getNodeType() == NodeType.RACK_STORAGE) {
            return false;
        }
        if (node.getNodeCode().startsWith("K")
                && !node.getNodeCode().contains("_ACCESS_")) {
            return false;
        }
        Map<String, Object> attributes = attributes(node.getRouteAttributes());
        boolean serviceOnly = booleanValue(
                node.getServiceOnly(),
                booleanValue(
                        attributes.get("service_only"),
                        node.getNodeType() != null && node.getNodeType().isServiceAccess()
                )
        );
        boolean transitAllowed = booleanValue(
                node.getTransitAllowed(),
                booleanValue(attributes.get("transit_allowed"), !serviceOnly)
        );
        return serviceOnly || transitAllowed;
    }

    private boolean isTraversable(WarehouseEdge edge) {
        return booleanValue(
                edge.getMobileRobotTraversable(),
                booleanValue(
                        attributes(edge.getRouteAttributes()).get("mobile_robot_traversable"),
                        true
                )
        );
    }

    private Map<String, Object> nodeProperties(
            WarehouseNode node,
            String warehouseCode,
            List<String> rackIds
    ) {
        Map<String, Object> values = attributes(node.getRouteAttributes());
        String type = nodeType(node);
        boolean serviceOnly = booleanValue(
                node.getServiceOnly(),
                booleanValue(
                        values.get("service_only"),
                        node.getNodeType() != null && node.getNodeType().isServiceAccess()
                )
        );
        boolean transitAllowed = booleanValue(
                node.getTransitAllowed(),
                booleanValue(values.get("transit_allowed"), !serviceOnly)
        );
        boolean holdingAllowed = booleanValue(
                node.getHoldingAllowed(),
                booleanValue(values.get("holding_allowed"), true)
        );
        int nodeCapacity = integerValue(
                node.getNodeCapacity(),
                integerValue(values.get("node_capacity"), 1)
        );
        String resourceType = stringValue(
                node.getResourceType(),
                stringValue(values.get("resource_type"), null)
        );
        String resourceCode = stringValue(
                node.getResourceCode(),
                firstString(values, "resource_code", "rack_id", "handoff_id",
                        "station_id", "buffer_id", "resource_id")
        );
        String side = stringValue(node.getSide(), stringValue(values.get("side"), null));

        remove(values,
                "service_only", "transit_allowed", "holding_allowed", "node_capacity",
                "resource_type", "resource_code", "resource_id", "rack_id", "handoff_id",
                "station_id", "buffer_id", "side", "rack_ids");

        put(values, "id", node.getNodeCode());
        put(values, "scope_id", warehouseCode + "::" + node.getNodeCode());
        put(values, "warehouse_id", warehouseCode);
        put(values, "type", type);
        put(values, "x", node.getX());
        put(values, "y", node.getY());
        put(values, "service_only", serviceOnly);
        put(values, "transit_allowed", transitAllowed);
        put(values, "holding_allowed", holdingAllowed);
        put(values, "node_capacity", nodeCapacity);
        put(values, "resource_type", resourceType);
        if (!rackIds.isEmpty()) {
            put(values, "rack_ids", rackIds);
        }
        if ("rack_access".equals(type)) {
            put(values, "rack_id", resourceCode);
            put(values, "side", side);
        } else if ("inbound_handoff_access".equals(type)) {
            put(values, "handoff_id", resourceCode);
        } else if ("outbound_station_access".equals(type)) {
            put(values, "station_id", resourceCode);
        } else if ("empty_tote_buffer_access".equals(type)) {
            put(values, "buffer_id", resourceCode);
        } else {
            put(values, "resource_id", resourceCode);
        }
        return values;
    }

    private Map<Long, List<String>> rackIdsByRouteNode(
            List<WarehouseNode> nodes,
            List<WarehouseEdge> edges
    ) {
        Map<Long, WarehouseNode> nodesById = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        WarehouseNode::getId,
                        value -> value
                ));
        Map<Long, Set<String>> rackIds = new LinkedHashMap<>();
        for (WarehouseEdge edge : edges) {
            WarehouseNode source = nodesById.get(edge.getFromNode().getId());
            WarehouseNode target = nodesById.get(edge.getToNode().getId());
            registerRackAccess(rackIds, source, target);
            registerRackAccess(rackIds, target, source);
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        rackIds.forEach((nodeId, values) -> result.put(
                nodeId,
                values.stream().sorted().toList()
        ));
        return result;
    }

    private void registerRackAccess(
            Map<Long, Set<String>> rackIds,
            WarehouseNode rack,
            WarehouseNode routeNode
    ) {
        if (rack == null || routeNode == null
                || rack.getNodeType() != NodeType.RACK_STORAGE
                || rack.getNodeCode() == null || rack.getNodeCode().isBlank()
                || !isRouteNode(routeNode)) {
            return;
        }
        rackIds.computeIfAbsent(routeNode.getId(), ignored -> new LinkedHashSet<>())
                .add(rack.getNodeCode());
    }

    private Map<String, Object> edgeRow(
            WarehouseEdge edge,
            String warehouseCode,
            String id,
            String source,
            String target
    ) {
        Map<String, Object> properties = attributes(edge.getRouteAttributes());
        double distance = edge.getDistance() == null ? 0.0 : edge.getDistance();
        double speed = doubleValue(
                edge.getSpeedLimitMps(),
                doubleValue(properties.get("speed_limit_mps"), 1.0)
        );
        long travelTime = longValue(
                edge.getNominalTravelTimeMs(),
                longValue(
                        properties.get("nominal_travel_time_ms"),
                        speed <= 0 ? 0 : Math.round(distance / speed * 1000)
                )
        );
        String physicalResource = stringValue(
                edge.getPhysicalResourceCode(),
                stringValue(
                        properties.get("physical_resource_code"),
                        stringValue(properties.get("resource_id"), edgeCode(edge))
                )
        );
        String edgeType = stringValue(
                edge.getEdgeType(),
                stringValue(properties.get("type"), "lane")
        );
        double cost = doubleValue(
                edge.getCost(),
                doubleValue(properties.get("cost"), distance)
        );
        boolean serviceOnly = booleanValue(
                edge.getServiceOnly(),
                booleanValue(properties.get("service_only"), false)
        );
        boolean mobileRobotTraversable = booleanValue(
                edge.getMobileRobotTraversable(),
                booleanValue(properties.get("mobile_robot_traversable"), true)
        );

        remove(properties,
                "direction", "speed_limit_mps", "nominal_travel_time_ms", "cost",
                "base_cost", "physical_resource_code", "resource_id", "service_only",
                "mobile_robot_traversable");

        put(properties, "id", id);
        put(properties, "scope_id", warehouseCode + "::" + id);
        put(properties, "warehouse_id", warehouseCode);
        put(properties, "source", source);
        put(properties, "target", target);
        put(properties, "type", edgeType);
        put(properties, "distance_m", distance);
        put(properties, "speed_limit_mps", speed);
        put(properties, "nominal_travel_time_ms", travelTime);
        put(properties, "cost", cost);
        put(properties, "physical_resource_code", physicalResource);
        put(properties, "service_only", serviceOnly);
        put(properties, "mobile_robot_traversable", mobileRobotTraversable);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_scope_id", warehouseCode + "::" + source);
        row.put("target_scope_id", warehouseCode + "::" + target);
        row.put("properties", properties);
        return row;
    }

    private String nodeType(WarehouseNode node) {
        return node.getNodeType() == null
                ? "route"
                : node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    private String edgeCode(WarehouseEdge edge) {
        return edge.getEdgeCode() == null || edge.getEdgeCode().isBlank()
                ? "E" + edge.getId()
                : edge.getEdgeCode();
    }

    private String warehouseCode(Long warehouseId) {
        return "WH-" + String.format(Locale.ROOT, "%03d", warehouseId);
    }

    private Map<String, Object> attributes(Map<String, Object> source) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (isNeo4jPropertyValue(value)) {
                    put(values, key, value);
                }
            });
        }
        return values;
    }

    private boolean isNeo4jPropertyValue(Object value) {
        if (value == null || isNeo4jScalar(value)) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.stream().allMatch(item -> item != null && isNeo4jScalar(item));
        }
        return false;
    }

    private boolean isNeo4jScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void remove(Map<String, Object> target, String... keys) {
        for (String key : keys) {
            target.remove(key);
        }
    }

    private String firstString(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = stringValue(values.get(key), null);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private int integerValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private double doubleValue(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
