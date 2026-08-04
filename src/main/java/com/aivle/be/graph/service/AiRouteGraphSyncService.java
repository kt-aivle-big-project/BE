package com.aivle.be.graph.service;

import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects the raw imported map into the RouteNode/TRAVERSES contract used by
 * the LARO AI service. It deliberately uses the raw map because the relational
 * graph collapses service-access nodes for BE playback.
 */
@Service
@RequiredArgsConstructor
public class AiRouteGraphSyncService {

    private static final Set<String> SERVICE_ACCESS_TYPES = Set.of(
            "rack_access",
            "inbound_handoff_access",
            "outbound_station_access",
            "empty_tote_buffer_access"
    );

    private final Neo4jClient neo4jClient;

    public String sync(Long warehouseId, WarehouseImportRequest.MapPayload map) {
        String aiWarehouseId = toAiWarehouseId(warehouseId);
        List<WarehouseImportRequest.MapNode> sourceNodes = List.copyOf(map.nodes());
        Set<String> sourceNodeIds = sourceNodes.stream()
                .map(WarehouseImportRequest.MapNode::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> logicalFacilityNodeIds = sourceNodes.stream()
                .filter(node -> isLogicalFacilityNode(node.type()))
                .map(WarehouseImportRequest.MapNode::id)
                .collect(java.util.stream.Collectors.toSet());

        List<Map<String, Object>> nodes = new ArrayList<>();
        sourceNodes.stream()
                .map(node -> toNode(aiWarehouseId, node))
                .forEach(nodes::add);

        List<Map<String, Object>> edges = new ArrayList<>();
        map.edges().stream()
                .filter(edge -> sourceNodeIds.contains(edge.source())
                        && sourceNodeIds.contains(edge.target()))
                .filter(edge -> !logicalFacilityNodeIds.contains(edge.source())
                        && !logicalFacilityNodeIds.contains(edge.target()))
                .map(edge -> toEdge(aiWarehouseId, edge))
                .forEach(edges::add);

        sourceNodes.stream()
                .filter(node -> "inbound_access".equals(lower(node.type())))
                .forEach(node -> addGeneratedServiceSpurs(
                        aiWarehouseId, node, "inbound_handoff_access",
                        node.id(), "HANDOFF", nodes, edges));
        sourceNodes.stream()
                .filter(node -> "outbound_access".equals(lower(node.type())))
                .forEach(node -> addGeneratedServiceSpurs(
                        aiWarehouseId, node, "outbound_station_access",
                        node.id(), "STATION", nodes, edges));

        neo4jClient.query("""
                MATCH (n:RouteNode {warehouse_id: $warehouseId})
                DETACH DELETE n
                """)
                .bind(aiWarehouseId).to("warehouseId")
                .run();

        neo4jClient.query("""
                UNWIND $nodes AS node
                MERGE (n:RouteNode {scope_id: node.scope_id})
                SET n += node
                FOREACH (_ IN CASE WHEN node.service_access THEN [1] ELSE [] END |
                    SET n:ServiceAccess)
                FOREACH (_ IN CASE WHEN node.type = 'rack_access' THEN [1] ELSE [] END |
                    SET n:RackAccess)
                FOREACH (_ IN CASE WHEN node.type = 'inbound_handoff_access' THEN [1] ELSE [] END |
                    SET n:InboundHandoffAccess)
                FOREACH (_ IN CASE WHEN node.type = 'outbound_station_access' THEN [1] ELSE [] END |
                    SET n:OutboundStationAccess)
                FOREACH (_ IN CASE WHEN node.type = 'empty_tote_buffer_access' THEN [1] ELSE [] END |
                    SET n:EmptyToteBufferAccess)
                REMOVE n.service_access
                """)
                .bind(nodes).to("nodes")
                .run();

        neo4jClient.query("""
                UNWIND $edges AS edge
                MATCH (source:RouteNode {scope_id: edge.source_scope_id})
                MATCH (target:RouteNode {scope_id: edge.target_scope_id})
                MERGE (source)-[r:TRAVERSES {scope_id: edge.scope_id}]->(target)
                SET r += edge
                REMOVE r.source_scope_id, r.target_scope_id
                """)
                .bind(edges).to("edges")
                .run();

        return aiWarehouseId;
    }

    public static String toAiWarehouseId(Long warehouseId) {
        return "WH-%03d".formatted(warehouseId);
    }

    public static List<String> inboundServiceAccessIds(String nodeId) {
        return serviceAccessIds(nodeId, "HANDOFF");
    }

    public static List<String> outboundServiceAccessIds(String nodeId) {
        return serviceAccessIds(nodeId, "STATION");
    }

    private static List<String> serviceAccessIds(String nodeId, String facilityKind) {
        return List.of(
                nodeId + "_" + facilityKind + "_ACCESS_A",
                nodeId + "_" + facilityKind + "_ACCESS_B"
        );
    }

    private boolean isLogicalFacilityNode(String type) {
        String normalized = lower(type);
        return "inbound".equals(normalized) || "outbound".equals(normalized);
    }

    private void addGeneratedServiceSpurs(
            String warehouseId,
            WarehouseImportRequest.MapNode source,
            String serviceType,
            String facilityId,
            String facilityKind,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges
    ) {
        List<String> accessIds = serviceAccessIds(source.id(), facilityKind);
        for (int index = 0; index < accessIds.size(); index++) {
            String accessId = accessIds.get(index);
            Map<String, Object> node = new LinkedHashMap<>();
            put(node, "warehouse_id", warehouseId);
            put(node, "scope_id", warehouseId + "::" + accessId);
            put(node, "id", accessId);
            put(node, "type", serviceType);
            put(node, "x", source.x() == null ? null : source.x() + (index == 0 ? -0.01 : 0.01));
            put(node, "y", source.y());
            if ("inbound_handoff_access".equals(serviceType)) {
                put(node, "handoff_id", facilityId);
            } else {
                put(node, "station_id", facilityId);
            }
            put(node, "service_access", true);
            put(node, "service_only", true);
            put(node, "transit_allowed", false);
            nodes.add(node);

            edges.add(generatedServiceEdge(
                    warehouseId, accessId + "_IN", source.id(), accessId));
            edges.add(generatedServiceEdge(
                    warehouseId, accessId + "_OUT", accessId, source.id()));
        }
    }

    private Map<String, Object> generatedServiceEdge(
            String warehouseId,
            String edgeId,
            String source,
            String target
    ) {
        Map<String, Object> edge = new LinkedHashMap<>();
        put(edge, "warehouse_id", warehouseId);
        put(edge, "scope_id", warehouseId + "::" + edgeId);
        put(edge, "id", edgeId);
        put(edge, "source", source);
        put(edge, "target", target);
        put(edge, "source_scope_id", warehouseId + "::" + source);
        put(edge, "target_scope_id", warehouseId + "::" + target);
        put(edge, "type", "service_access");
        put(edge, "distance_m", 0.1);
        put(edge, "speed_limit_mps", 1.0);
        put(edge, "nominal_travel_time_ms", 100L);
        put(edge, "cost", 0.1);
        return edge;
    }

    private Map<String, Object> toNode(
            String warehouseId,
            WarehouseImportRequest.MapNode source
    ) {
        String type = canonicalNodeType(lower(source.type()));
        Map<String, Object> node = new LinkedHashMap<>();
        put(node, "warehouse_id", warehouseId);
        put(node, "scope_id", warehouseId + "::" + source.id());
        put(node, "id", source.id());
        put(node, "type", type);
        put(node, "x", source.x());
        put(node, "y", source.y());
        put(node, "rack_id", source.rack_id());
        put(node, "handoff_id", facilityId(
                type, "inbound_handoff_access", source.handoff_id(), source.id()));
        put(node, "station_id", facilityId(
                type, "outbound_station_access", source.station_id(), source.id()));
        put(node, "buffer_id", facilityId(
                type, "empty_tote_buffer_access", source.buffer_id(), source.id()));
        put(node, "resource_id", source.resource_id());
        put(node, "side", source.side());
        put(node, "adjacent_route_node", source.adjacent_route_node());

        boolean serviceAccess = SERVICE_ACCESS_TYPES.contains(type);
        put(node, "service_access", serviceAccess);
        put(node, "service_only",
                source.service_only() != null ? source.service_only() : serviceAccess);
        put(node, "transit_allowed",
                source.transit_allowed() != null ? source.transit_allowed() : !serviceAccess);
        return node;
    }

    private String canonicalNodeType(String type) {
        return type;
    }

    private String facilityId(
            String nodeType,
            String facilityNodeType,
            String explicitId,
            String nodeId
    ) {
        if (explicitId != null && !explicitId.isBlank()) {
            return explicitId;
        }
        return facilityNodeType.equals(nodeType) ? nodeId : null;
    }

    private Map<String, Object> toEdge(
            String warehouseId,
            WarehouseImportRequest.MapEdge source
    ) {
        double distance = source.distance_m() == null ? 0.0 : source.distance_m();
        double speed = source.speed_limit_mps() == null ? 1.0 : source.speed_limit_mps();
        long travelTime = source.nominal_travel_time_ms() == null
                ? Math.round(distance / speed * 1_000)
                : source.nominal_travel_time_ms();

        Map<String, Object> edge = new LinkedHashMap<>();
        put(edge, "warehouse_id", warehouseId);
        put(edge, "scope_id", warehouseId + "::" + source.id());
        put(edge, "id", source.id());
        put(edge, "source", source.source());
        put(edge, "target", source.target());
        put(edge, "source_scope_id", warehouseId + "::" + source.source());
        put(edge, "target_scope_id", warehouseId + "::" + source.target());
        put(edge, "type", lower(source.type()));
        put(edge, "distance_m", distance);
        put(edge, "speed_limit_mps", speed);
        put(edge, "nominal_travel_time_ms", travelTime);
        put(edge, "cost", source.cost() == null ? distance : source.cost());
        return edge;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
