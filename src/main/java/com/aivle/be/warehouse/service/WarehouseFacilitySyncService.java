package com.aivle.be.warehouse.service;

import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WarehouseFacilitySyncService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void synchronizeOutboundFacilities(
            Long warehouseId,
            List<WarehouseNode> persistedNodes,
            List<WarehouseEdge> persistedEdges
    ) {
        Map<String, WarehouseNode> nodeByCode = new LinkedHashMap<>();
        List<WarehouseImportRequest.MapNode> nodes = new ArrayList<>();
        for (WarehouseNode node : persistedNodes) {
            if (node.getNodeCode() == null || node.getNodeCode().isBlank()) {
                continue;
            }
            nodeByCode.put(node.getNodeCode(), node);
            String nodeType = node.getNodeType() == null
                    ? "route"
                    : node.getNodeType().name().toLowerCase(Locale.ROOT);
            String resourceCode = node.getResourceCode();
            nodes.add(new WarehouseImportRequest.MapNode(
                    node.getNodeCode(),
                    nodeType,
                    node.getX(),
                    node.getY(),
                    null,
                    node.getSide(),
                    "inbound_handoff_access".equals(nodeType) ? resourceCode : null,
                    "outbound_station_access".equals(nodeType) ? resourceCode : null,
                    "empty_tote_buffer_access".equals(nodeType) ? resourceCode : null,
                    resourceCode,
                    null,
                    stringAttribute(node.getRouteAttributes(), "label"),
                    null,
                    null,
                    null,
                    node.getServiceOnly(),
                    node.getTransitAllowed(),
                    node.getHoldingAllowed(),
                    node.getNodeCapacity(),
                    booleanAttribute(node.getRouteAttributes(), "active_for_new_work"),
                    booleanAttribute(node.getRouteAttributes(), "legacy_direct_route"),
                    node.getRouteAttributes()
            ));
        }

        List<WarehouseImportRequest.MapEdge> edges = persistedEdges.stream()
                .filter(edge -> edge.getFromNode() != null && edge.getToNode() != null)
                .map(edge -> new WarehouseImportRequest.MapEdge(
                        edge.getEdgeCode(),
                        edge.getFromNode().getNodeCode(),
                        edge.getToNode().getNodeCode(),
                        edge.getDistance(),
                        edge.getEdgeType(),
                        edge.getDirectionType() == null ? null : edge.getDirectionType().name(),
                        edge.getSpeedLimitMps(),
                        edge.getNominalTravelTimeMs(),
                        edge.getCost(),
                        edge.getPhysicalResourceCode(),
                        edge.getPhysicalResourceCode(),
                        edge.getServiceOnly(),
                        edge.getMobileRobotTraversable(),
                        edge.getRouteAttributes()
                ))
                .toList();

        if (!nodes.isEmpty() && !edges.isEmpty()) {
            synchronizeOutboundFacilities(
                    warehouseId,
                    new WarehouseImportRequest.MapPayload(nodes, edges),
                    nodeByCode
            );
        }
    }

    public void synchronizeOutboundFacilities(
            Long warehouseId,
            WarehouseImportRequest.MapPayload map,
            Map<String, WarehouseNode> nodeByCode
    ) {
        if (!facilityTableExists()) {
            return;
        }

        Map<String, WarehouseImportRequest.MapNode> nodes = new LinkedHashMap<>();
        for (WarehouseImportRequest.MapNode node : map.nodes()) {
            nodes.put(node.id(), node);
        }

        Map<String, List<WarehouseImportRequest.MapEdge>> incident = new LinkedHashMap<>();
        for (WarehouseImportRequest.MapEdge edge : map.edges()) {
            incident.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge);
            incident.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge);
        }

        List<StationHub> hubs = nodes.values().stream()
                .filter(node -> "route".equals(normalized(node.type())))
                .map(node -> stationHub(node, nodes, incident.getOrDefault(node.id(), List.of())))
                .filter(value -> value.endpointResourceCodes().size() >= 2)
                .sorted(Comparator
                        .comparingDouble((StationHub value) -> number(value.hub().y()))
                        .thenComparing(value -> value.hub().id()))
                .toList();

        if (hubs.isEmpty()) {
            return;
        }

        List<EndpointGroup> endpointGroups = endpointGroups(nodes, hubs);
        List<String> chuteCodes = existingChuteCodes(warehouseId);
        while (chuteCodes.size() < endpointGroups.size()) {
            chuteCodes.add("O_" + (chuteCodes.size() + 1));
        }
        Map<String, String> chuteByEndpointResource = new LinkedHashMap<>();
        for (int index = 0; index < endpointGroups.size(); index += 1) {
            chuteByEndpointResource.put(endpointGroups.get(index).resourceCode(), chuteCodes.get(index));
        }

        jdbcTemplate.update(
                """
                UPDATE laro_ext.facility
                   SET active = false, updated_at = now()
                 WHERE warehouse_id = ?
                   AND facility_type IN ('OUTBOUND_CHUTE','OUTBOUND_STATION','STATION_ROBOT')
                """,
                warehouseId
        );

        for (EndpointGroup endpoint : endpointGroups) {
            String chuteCode = chuteByEndpointResource.get(endpoint.resourceCode());
            WarehouseNode node = nodeByCode.get(endpoint.representativeNodeCode());
            upsertFacility(
                    warehouseId,
                    chuteCode,
                    "OUTBOUND_CHUTE",
                    node == null ? null : node.getId(),
                    List.of(),
                    List.of(),
                    null,
                    "AVAILABLE",
                    Map.of(
                            "chute_id", chuteCode,
                            "label", endpoint.label(),
                            "visual_endpoint_resource_code", endpoint.resourceCode()
                    )
            );
        }

        for (int index = 0; index < hubs.size(); index += 1) {
            StationHub hub = hubs.get(index);
            String stationId = index == 0 ? "OUT_STATION_UPPER" :
                    index == 1 ? "OUT_STATION_LOWER" : "OUT_STATION_" + (index + 1);
            String stationRobotId = String.format(Locale.ROOT, "SR-OUT-%02d", index + 1);
            List<String> accessNodeCodes = hub.boundaryNodeCodes().stream().limit(4).toList();
            List<String> servedChutes = hub.endpointResourceCodes().stream()
                    .map(chuteByEndpointResource::get)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            WarehouseNode hubNode = nodeByCode.get(hub.hub().id());

            upsertFacility(
                    warehouseId,
                    stationId,
                    "OUTBOUND_STATION",
                    hubNode == null ? null : hubNode.getId(),
                    accessNodeCodes,
                    servedChutes,
                    Math.max(1, accessNodeCodes.size()),
                    "AVAILABLE",
                    Map.of(
                            "station_id", stationId,
                            "station_robot_id", stationRobotId,
                            "hub_node_code", hub.hub().id(),
                            "access_node_ids", accessNodeCodes,
                            "served_chute_ids", servedChutes,
                            "tote_buffer_capacity", Math.max(1, accessNodeCodes.size()),
                            "serves_all_destinations", false
                    )
            );
            upsertFacility(
                    warehouseId,
                    stationRobotId,
                    "STATION_ROBOT",
                    hubNode == null ? null : hubNode.getId(),
                    List.of(),
                    List.of(),
                    16,
                    "IDLE",
                    Map.of(
                            "station_id", stationId,
                            "station_robot_id", stationRobotId,
                            "hub_node_code", hub.hub().id(),
                            "max_orders_per_wave", 16,
                            "items_per_tick", 1
                    )
            );
        }
    }

    private StationHub stationHub(
            WarehouseImportRequest.MapNode hub,
            Map<String, WarehouseImportRequest.MapNode> nodes,
            List<WarehouseImportRequest.MapEdge> edges
    ) {
        Set<String> endpointResources = new LinkedHashSet<>();
        Set<String> boundaries = new LinkedHashSet<>();
        for (WarehouseImportRequest.MapEdge edge : edges) {
            String peerCode = edge.source().equals(hub.id()) ? edge.target() : edge.source();
            WarehouseImportRequest.MapNode peer = nodes.get(peerCode);
            if (peer == null) {
                continue;
            }
            if (isOutboundServiceEdge(edge)
                    && "outbound_station_access".equals(normalized(peer.type()))) {
                endpointResources.add(resourceCode(peer));
            } else if (!isOutboundServiceEdge(edge)
                    && !Boolean.FALSE.equals(edge.mobile_robot_traversable())
                    && "route".equals(normalized(peer.type()))) {
                boundaries.add(peer.id());
            }
        }
        List<String> sortedBoundaries = boundaries.stream()
                .sorted(Comparator
                        .comparingDouble((String code) -> number(nodes.get(code).y()))
                        .thenComparing(code -> code))
                .toList();
        return new StationHub(
                hub,
                List.copyOf(endpointResources),
                sortedBoundaries
        );
    }

    private List<EndpointGroup> endpointGroups(
            Map<String, WarehouseImportRequest.MapNode> nodes,
            List<StationHub> hubs
    ) {
        Set<String> usedResources = new LinkedHashSet<>();
        hubs.forEach(hub -> usedResources.addAll(hub.endpointResourceCodes()));
        Map<String, List<WarehouseImportRequest.MapNode>> grouped = new LinkedHashMap<>();
        nodes.values().stream()
                .filter(node -> "outbound_station_access".equals(normalized(node.type())))
                .filter(node -> usedResources.contains(resourceCode(node)))
                .forEach(node -> grouped.computeIfAbsent(resourceCode(node), ignored -> new ArrayList<>()).add(node));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<WarehouseImportRequest.MapNode> members = entry.getValue();
                    WarehouseImportRequest.MapNode representative = members.stream()
                            .min(Comparator.comparingDouble(value -> number(value.y())))
                            .orElseThrow();
                    double y = members.stream().mapToDouble(value -> number(value.y())).average().orElse(0);
                    Object rawLabel = representative.routeAttributes().get("label");
                    String label = rawLabel == null ? entry.getKey() : String.valueOf(rawLabel);
                    return new EndpointGroup(entry.getKey(), representative.id(), label, y);
                })
                .sorted(Comparator.comparingDouble(EndpointGroup::y).thenComparing(EndpointGroup::resourceCode))
                .toList();
    }

    private List<String> existingChuteCodes(Long warehouseId) {
        return new ArrayList<>(jdbcTemplate.queryForList(
                """
                SELECT facility_code
                  FROM laro_ext.facility
                 WHERE warehouse_id = ? AND facility_type = 'OUTBOUND_CHUTE'
                 ORDER BY facility_code
                """,
                String.class,
                warehouseId
        ));
    }

    private void upsertFacility(
            Long warehouseId,
            String code,
            String type,
            Long nodeId,
            List<String> accessNodes,
            List<String> destinations,
            Integer capacity,
            String status,
            Map<String, Object> metadata
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO laro_ext.facility (
                    warehouse_id, facility_code, facility_type, node_id,
                    access_node_codes, served_destination_codes, capacity,
                    status, metadata, active, updated_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb, true, now())
                ON CONFLICT (warehouse_id, facility_code) DO UPDATE SET
                    facility_type = excluded.facility_type,
                    node_id = excluded.node_id,
                    access_node_codes = excluded.access_node_codes,
                    served_destination_codes = excluded.served_destination_codes,
                    capacity = excluded.capacity,
                    status = excluded.status,
                    metadata = excluded.metadata,
                    active = true,
                    updated_at = now()
                """,
                warehouseId,
                code,
                type,
                nodeId,
                json(accessNodes),
                json(destinations),
                capacity,
                status,
                json(metadata)
        );
    }

    private boolean facilityTableExists() {
        Boolean value = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (ResultSet tables = connection.getMetaData().getTables(
                    connection.getCatalog(), "laro_ext", "facility", new String[]{"TABLE"}
            )) {
                return tables.next();
            }
        });
        return Boolean.TRUE.equals(value);
    }

    private boolean isOutboundServiceEdge(WarehouseImportRequest.MapEdge edge) {
        String type = normalized(edge.type());
        return Boolean.TRUE.equals(edge.service_only())
                || type.equals("outbound_service")
                || type.equals("station_service");
    }

    private String resourceCode(WarehouseImportRequest.MapNode node) {
        String value = node.station_id();
        if (value == null || value.isBlank()) {
            value = node.resourceCode();
        }
        return value == null || value.isBlank() ? node.id() : value;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double number(Double value) {
        return value == null ? 0 : value;
    }

    private String stringAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.parseBoolean(String.valueOf(value));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize facility metadata", exception);
        }
    }

    private record StationHub(
            WarehouseImportRequest.MapNode hub,
            List<String> endpointResourceCodes,
            List<String> boundaryNodeCodes
    ) {}

    private record EndpointGroup(
            String resourceCode,
            String representativeNodeCode,
            String label,
            double y
    ) {}
}
