package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.graph.event.WarehouseGraphChangedEvent;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import com.aivle.be.warehouse.dto.WarehouseImportResponse;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WarehouseImportService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseImportService.class);

    private static final Map<String, NodeType> NODE_TYPES = Map.ofEntries(
            Map.entry("route", NodeType.ROUTE),
            Map.entry("inbound_access", NodeType.ROUTE),
            Map.entry("outbound_access", NodeType.ROUTE),
            Map.entry("route_charge_junction", NodeType.ROUTE_CHARGE_JUNCTION),
            Map.entry("rack_storage", NodeType.RACK_STORAGE),
            Map.entry("rack_access", NodeType.RACK_ACCESS),
            Map.entry("inbound_handoff_access", NodeType.INBOUND_HANDOFF_ACCESS),
            Map.entry("outbound_station_access", NodeType.OUTBOUND_STATION_ACCESS),
            Map.entry("empty_tote_buffer_access", NodeType.EMPTY_TOTE_BUFFER_ACCESS),
            Map.entry("inbound", NodeType.INBOUND),
            Map.entry("outbound", NodeType.OUTBOUND),
            Map.entry("charging_slot", NodeType.CHARGING_SLOT),
            Map.entry("parking_slot", NodeType.PARKING_SLOT)
    );

    /** 노드 타입 -> 구역 이름 */
    private static final Map<NodeType, String> ZONE_NAMES = Map.ofEntries(
            Map.entry(NodeType.ROUTE, "MOVING_ZONE"),
            Map.entry(NodeType.ROUTE_CHARGE_JUNCTION, "MOVING_ZONE"),
            Map.entry(NodeType.RACK_STORAGE, "STORAGE_ZONE"),
            Map.entry(NodeType.RACK_ACCESS, "STORAGE_ZONE"),
            Map.entry(NodeType.INBOUND_HANDOFF_ACCESS, "INBOUND_ZONE"),
            Map.entry(NodeType.OUTBOUND_STATION_ACCESS, "OUTBOUND_ZONE"),
            Map.entry(NodeType.EMPTY_TOTE_BUFFER_ACCESS, "OUTBOUND_ZONE"),
            Map.entry(NodeType.INBOUND, "INBOUND_ZONE"),
            Map.entry(NodeType.OUTBOUND, "OUTBOUND_ZONE"),
            Map.entry(NodeType.CHARGING_SLOT, "CHARGING_ZONE"),
            Map.entry(NodeType.PARKING_SLOT, "MOVING_ZONE")
    );

    private static final String RACK_ACCESS_TYPE = "rack_access";
    private static final String ACCESS_MARKER = "_ACCESS";

    private static final int DEFAULT_ROBOT_COUNT = 6;
    private static final double DEFAULT_CHARGING_POWER = 50.0;

    private static final int RACK_LEVELS = 3;
    private static final Set<TaskStatus> OPERATIONAL_TASK_STATUSES = Set.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );
    private static final Set<SimulationRunStatus> OPERATIONAL_RUN_STATUSES = Set.of(
            SimulationRunStatus.CREATED,
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED,
            SimulationRunStatus.QUIESCING,
            SimulationRunStatus.REPLANNING,
            SimulationRunStatus.PENDING_ACTIVATION
    );

    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final WarehouseZoneRepository warehouseZoneRepository;
    private final ChargingStationRepository chargingStationRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;
    private final ScenarioRepository scenarioRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WarehouseFacilitySyncService warehouseFacilitySyncService;

    @Transactional
    public WarehouseImportResponse importWarehouse(WarehouseImportRequest request, Long loginUserId) {
        return importWarehouse(request, loginUserId, false);
    }

    @Transactional
    public WarehouseImportResponse importWarehouseWithScenarioPresets(
            WarehouseImportRequest request,
            Long loginUserId
    ) {
        return importWarehouse(request, loginUserId, true);
    }

    private WarehouseImportResponse importWarehouse(
            WarehouseImportRequest request,
            Long loginUserId,
            boolean createScenarioPresets
    ) {
        User owner = findOwner(request.userId(), loginUserId);

        int[] dimensions = resolveDimensions(request);

        Warehouse warehouse = warehouseRepository.save(
                Warehouse.create(
                        request.name(),
                        dimensions[0],
                        dimensions[1],
                        owner,
                        request.location(),
                        request.description(),
                        request.status()
                )
        );

        // 1) 노드
        List<WarehouseNode> nodes = createNodes(warehouse, request.map().nodes());

        if (nodes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Map<String, WarehouseNode> nodeByCode = new LinkedHashMap<>();
        for (WarehouseNode node : nodes) {
            nodeByCode.put(node.getNodeCode(), node);
        }

        // 2) 구역 (노드 좌표에서 범위를 계산)
        createZones(warehouse, nodes);

        // 3) 간선
        int edgeCount = createEdges(request.map(), nodeByCode);

        // 4) 설비
        List<WarehouseNode> chargingSlots = nodesOf(nodes, NodeType.CHARGING_SLOT);
        List<WarehouseNode> racks = nodesOf(nodes, NodeType.RACK_STORAGE);

        createChargingStations(warehouse, chargingSlots);
        List<StorageLocation> locations = createStorageLocations(warehouse, racks);
        int stockedLevels = createInitialInventory(warehouse, locations);
        warehouseFacilitySyncService.synchronizeOutboundFacilities(
                warehouse.getId(), request.map(), nodeByCode
        );
        int robotCount = createRobots(warehouse, chargingSlots, request.robotCount());
        if (createScenarioPresets) {
            createScenarioPresets(warehouse, robotCount);
        }

        Set<String> importedCodes = request.map().nodes().stream()
                .map(WarehouseImportRequest.MapNode::id)
                .collect(java.util.stream.Collectors.toSet());
        int importedNodeCount = (int) nodes.stream()
                .filter(node -> importedCodes.contains(node.getNodeCode()))
                .count();
        int skipped = request.map().nodes().size() - importedNodeCount;

        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(warehouse.getId()));

        log.info("[창고 가져오기] {} (id={}) 노드 {}, 간선 {}, 랙 {}, 충전소 {}, 로봇 {}, 초기 재고 {}칸 (제외 {})",
                warehouse.getName(), warehouse.getId(),
                nodes.size(), edgeCount, racks.size(), chargingSlots.size(),
                robotCount, stockedLevels, skipped);

        return new WarehouseImportResponse(
                warehouse.getId(),
                warehouse.getName(),
                nodes.size(),
                importedNodeCount,
                edgeCount,
                racks.size(),
                chargingSlots.size(),
                robotCount,
                skipped
        );
    }

    @Transactional
    public WarehouseImportResponse updateWarehouseLayout(
            Long warehouseId,
            WarehouseImportRequest request
    ) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
        warehouse.update(
                request.name(),
                request.width(),
                request.height(),
                request.location(),
                request.description(),
                request.status()
        );

        List<WarehouseNode> existingNodes = warehouseNodeRepository
                .findAllByWarehouse_Id(warehouseId);
        Map<String, WarehouseNode> nodeByCode = new LinkedHashMap<>();
        existingNodes.forEach(node -> nodeByCode.put(node.getNodeCode(), node));

        Set<String> requestedNodeCodes = request.map().nodes().stream()
                .map(WarehouseImportRequest.MapNode::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<WarehouseNode> removedNodes = existingNodes.stream()
                .filter(WarehouseNode::isActive)
                .filter(node -> !requestedNodeCodes.contains(node.getNodeCode()))
                .toList();
        Set<Long> removedNodeIds = removedNodes.stream()
                .map(WarehouseNode::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!removedNodeIds.isEmpty()
                && taskRepository.countOperationalReferences(
                        warehouseId,
                        removedNodeIds,
                        OPERATIONAL_TASK_STATUSES,
                        OPERATIONAL_RUN_STATUSES
                ) > 0) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NODE_IN_ACTIVE_TASK);
        }
        Set<Long> protectedNodeIds = new LinkedHashSet<>();
        storageLocationRepository.findAllByWarehouse_Id(warehouseId)
                .forEach(value -> protectedNodeIds.add(value.getNode().getId()));
        chargingStationRepository.findAllByWarehouse_Id(warehouseId)
                .forEach(value -> protectedNodeIds.add(value.getNode().getId()));
        robotRepository.findAllByWarehouse_Id(warehouseId).stream()
                .map(Robot::getNodeId)
                .filter(java.util.Objects::nonNull)
                .forEach(protectedNodeIds::add);
        boolean removesProtectedNode = existingNodes.stream()
                .anyMatch(node -> node.isActive()
                        && protectedNodeIds.contains(node.getId())
                        && !requestedNodeCodes.contains(node.getNodeCode()));
        if (removesProtectedNode) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        int skipped = 0;
        List<String> skippedNodes = new ArrayList<>();
        for (WarehouseImportRequest.MapNode raw : request.map().nodes()) {
            NodeType nodeType = NODE_TYPES.get(lower(raw.type()));
            if (nodeType == null) {
                skipped += 1;
                skippedNodes.add("%s(%s)".formatted(raw.id(), raw.type()));
                continue;
            }
            WarehouseNode.RouteProperties properties = new WarehouseNode.RouteProperties(
                    raw.service_only(),
                    raw.transit_allowed(),
                    raw.holding_allowed(),
                    raw.node_capacity(),
                    raw.resourceType(),
                    raw.resourceCode(),
                    raw.side()
            );
            WarehouseNode node = nodeByCode.get(raw.id());
            if (node == null) {
                node = WarehouseNode.create(
                        warehouse,
                        ZONE_NAMES.get(nodeType),
                        raw.x(),
                        raw.y(),
                        raw.id(),
                        nodeType,
                        properties,
                        raw.routeAttributes()
                );
                warehouseNodeRepository.save(node);
                nodeByCode.put(raw.id(), node);
            } else {
                node.update(
                        ZONE_NAMES.get(nodeType),
                        raw.x(),
                        raw.y(),
                        raw.id(),
                        nodeType,
                        properties,
                        raw.routeAttributes()
                );
                node.activate();
            }
        }
        warehouseNodeRepository.flush();

        if (!skippedNodes.isEmpty()) {
            log.warn("[창고 수정] id={} 종류를 알 수 없어 저장하지 않은 노드 {}개: {}",
                    warehouseId, skippedNodes.size(),
                    skippedNodes.size() > 20
                            ? skippedNodes.subList(0, 20) + " ..."
                            : skippedNodes);
        }

        List<WarehouseEdge> existingEdges = warehouseEdgeRepository
                .findAllByFromNode_Warehouse_Id(warehouseId);
        Map<String, WarehouseEdge> edgeByCode = new LinkedHashMap<>();
        existingEdges.forEach(edge -> edgeByCode.put(edge.getEdgeCode(), edge));
        Set<String> requestedEdgeCodes = new LinkedHashSet<>();
        List<String> droppedEdges = new ArrayList<>();

        for (WarehouseImportRequest.MapEdge raw : request.map().edges()) {
            WarehouseNode source = nodeByCode.get(raw.source());
            WarehouseNode target = nodeByCode.get(raw.target());
            if (source == null || target == null || source == target) {
                droppedEdges.add("%s(%s->%s)".formatted(
                        raw.id(), raw.source(), raw.target()));
                continue;
            }
            String edgeCode = raw.id();
            if (edgeCode == null || edgeCode.isBlank()) {
                edgeCode = raw.source() + "::" + raw.target();
            }
            if (!requestedEdgeCodes.add(edgeCode)) {
                droppedEdges.add("%s(중복, %s->%s)".formatted(
                        edgeCode, raw.source(), raw.target()));
                continue;
            }
            double distance = raw.distance_m() == null ? 1.0 : raw.distance_m();
            WarehouseEdge.RouteProperties properties = new WarehouseEdge.RouteProperties(
                    raw.type(),
                    raw.speed_limit_mps(),
                    raw.nominal_travel_time_ms(),
                    raw.cost(),
                    raw.physicalResourceCode(),
                    raw.service_only(),
                    raw.mobile_robot_traversable()
            );
            WarehouseEdge edge = edgeByCode.get(edgeCode);
            if (edge == null) {
                warehouseEdgeRepository.save(WarehouseEdge.create(
                        source,
                        target,
                        distance,
                        directionOf(raw.direction()),
                        edgeCode,
                        properties,
                        raw.routeAttributes()
                ));
            } else {
                edge.update(
                        source,
                        target,
                        distance,
                        directionOf(raw.direction()),
                        edgeCode,
                        properties,
                        raw.routeAttributes()
                );
            }
        }

        List<WarehouseEdge> removedEdges = existingEdges.stream()
                .filter(edge -> !requestedEdgeCodes.contains(edge.getEdgeCode()))
                .toList();
        warehouseEdgeRepository.deleteAll(removedEdges);
        warehouseEdgeRepository.flush();

        log.info("[창고 수정] id={} 요청 노드 {} (제외 {}), 요청 간선 {} -> 저장 {}, 삭제 {}",
                warehouseId, request.map().nodes().size(), skipped,
                request.map().edges().size(), requestedEdgeCodes.size(),
                removedEdges.size());
        if (!droppedEdges.isEmpty()) {
            log.warn("[창고 수정] id={} 끝점을 찾지 못해 버린 간선 {}개: {}",
                    warehouseId, droppedEdges.size(),
                    droppedEdges.size() > 20
                            ? droppedEdges.subList(0, 20) + " ..."
                            : droppedEdges);
        }

        removedNodes.forEach(WarehouseNode::retire);
        warehouseNodeRepository.flush();

        Set<Long> storageNodeIds = storageLocationRepository
                .findAllByWarehouse_Id(warehouseId).stream()
                .map(value -> value.getNode().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<WarehouseNode> newRacks = nodeByCode.values().stream()
                .filter(node -> node.getNodeType() == NodeType.RACK_STORAGE)
                .filter(node -> requestedNodeCodes.contains(node.getNodeCode()))
                .filter(node -> !storageNodeIds.contains(node.getId()))
                .toList();
        createStorageLocations(warehouse, newRacks);

        Set<Long> chargingNodeIds = chargingStationRepository
                .findAllByWarehouse_Id(warehouseId).stream()
                .map(value -> value.getNode().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<WarehouseNode> newChargingSlots = nodeByCode.values().stream()
                .filter(node -> node.getNodeType() == NodeType.CHARGING_SLOT)
                .filter(node -> requestedNodeCodes.contains(node.getNodeCode()))
                .filter(node -> !chargingNodeIds.contains(node.getId()))
                .toList();
        createChargingStations(warehouse, newChargingSlots);

        List<WarehouseNode> activeChargingSlots = nodeByCode.values().stream()
                .filter(node -> node.getNodeType() == NodeType.CHARGING_SLOT)
                .filter(node -> requestedNodeCodes.contains(node.getNodeCode()))
                .toList();
        synchronizeRobotHomeNodes(warehouseId, activeChargingSlots);

        // The physical facility contract must advance with the same map
        // revision.  Otherwise FE/Neo4j show the edited two-robot topology
        warehouseFacilitySyncService.synchronizeOutboundFacilities(
                warehouseId, request.map(), nodeByCode
        );

        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(warehouseId));
        int rackCount = (int) nodeByCode.values().stream()
                .filter(node -> node.getNodeType() == NodeType.RACK_STORAGE)
                .filter(node -> requestedNodeCodes.contains(node.getNodeCode()))
                .count();
        int chargingCount = (int) nodeByCode.values().stream()
                .filter(node -> node.getNodeType() == NodeType.CHARGING_SLOT)
                .filter(node -> requestedNodeCodes.contains(node.getNodeCode()))
                .count();
        int robotCount = robotRepository.findAllByWarehouse_Id(warehouseId).size();

        return new WarehouseImportResponse(
                warehouseId,
                warehouse.getName(),
                requestedNodeCodes.size() - skipped,
                requestedNodeCodes.size() - skipped,
                requestedEdgeCodes.size(),
                rackCount,
                chargingCount,
                robotCount,
                skipped
        );
    }

    /* =========================================================
       노드
    ========================================================= */

    private List<WarehouseNode> createNodes(
            Warehouse warehouse,
            List<WarehouseImportRequest.MapNode> rawNodes
    ) {
        List<WarehouseNode> nodes = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();

        for (WarehouseImportRequest.MapNode raw : rawNodes) {
            NodeType nodeType = NODE_TYPES.get(lower(raw.type()));

            if (nodeType == null || !seenCodes.add(raw.id())) {
                continue;
            }

            nodes.add(WarehouseNode.create(
                    warehouse,
                    ZONE_NAMES.get(nodeType),
                    raw.x(),
                    raw.y(),
                    raw.id(),
                    nodeType,
                    new WarehouseNode.RouteProperties(
                            raw.service_only(),
                            raw.transit_allowed(),
                            raw.holding_allowed(),
                            raw.node_capacity(),
                            raw.resourceType(),
                            raw.resourceCode(),
                            raw.side()
                    ),
                    raw.routeAttributes()
            ));
        }

        Map<String, List<double[]>> rackPoints = new LinkedHashMap<>();

        for (WarehouseImportRequest.MapNode raw : rawNodes) {
            if (!RACK_ACCESS_TYPE.equals(lower(raw.type()))) {
                continue;
            }

            String rackCode = rackCodeOf(raw);

            if (rackCode == null || raw.x() == null || raw.y() == null) {
                continue;
            }

            rackPoints.computeIfAbsent(rackCode, key -> new ArrayList<>())
                    .add(new double[]{raw.x(), raw.y()});
        }

        for (Map.Entry<String, List<double[]>> entry : rackPoints.entrySet()) {
            if (!seenCodes.add(entry.getKey())) {
                continue;
            }

            List<double[]> points = entry.getValue();
            double x = points.stream().mapToDouble(point -> point[0]).average().orElse(0);
            double y = points.stream().mapToDouble(point -> point[1]).average().orElse(0);

            nodes.add(WarehouseNode.create(
                    warehouse,
                    ZONE_NAMES.get(NodeType.RACK_STORAGE),
                    round(x),
                    round(y),
                    entry.getKey(),
                    NodeType.RACK_STORAGE,
                    new WarehouseNode.RouteProperties(
                            false,
                            false,
                            false,
                            1,
                            "RACK",
                            entry.getKey(),
                            null
                    ),
                    Map.of()
            ));
        }

        return warehouseNodeRepository.saveAll(nodes);
    }

    private String rackCodeOf(WarehouseImportRequest.MapNode raw) {
        if (raw.rack_id() != null && !raw.rack_id().isBlank()) {
            return raw.rack_id();
        }

        int marker = raw.id().indexOf(ACCESS_MARKER);
        return marker > 0 ? raw.id().substring(0, marker) : null;
    }

    /* =========================================================
       간선
    ========================================================= */

    private int createEdges(
            WarehouseImportRequest.MapPayload map,
            Map<String, WarehouseNode> nodeByCode
    ) {
        // 접근 자리 -> 랙
        // 방향별로 모은다
        List<WarehouseEdge> edges = new ArrayList<>();
        Set<String> usedCodes = new LinkedHashSet<>();

        for (WarehouseImportRequest.MapEdge raw : map.edges()) {
            WarehouseNode source = nodeByCode.get(raw.source());
            WarehouseNode target = nodeByCode.get(raw.target());
            String edgeCode = raw.id();

            if (source == null || target == null || source == target) {
                continue;
            }
            if (edgeCode == null || edgeCode.isBlank()) {
                edgeCode = raw.source() + "::" + raw.target();
            }
            if (!usedCodes.add(edgeCode)) {
                continue;
            }

            edges.add(WarehouseEdge.create(
                    source,
                    target,
                    raw.distance_m() == null ? 1.0 : raw.distance_m(),
                    directionOf(raw.direction()),
                    edgeCode,
                    new WarehouseEdge.RouteProperties(
                            raw.type(),
                            raw.speed_limit_mps(),
                            raw.nominal_travel_time_ms(),
                            raw.cost(),
                            raw.physicalResourceCode(),
                            raw.service_only(),
                            raw.mobile_robot_traversable()
                    ),
                    raw.routeAttributes()
            ));
        }

        warehouseEdgeRepository.saveAll(edges);
        return edges.size();
    }

    /* =========================================================
       구역 · 설비
    ========================================================= */

    private void createZones(Warehouse warehouse, List<WarehouseNode> nodes) {
        record ZoneSpec(String name, WarehouseZone.ZoneType type, String description, List<NodeType> members) {}

        List<ZoneSpec> specs = List.of(
                new ZoneSpec("MOVING_ZONE", WarehouseZone.ZoneType.MOVING, "이동 통로",
                        List.of(NodeType.ROUTE, NodeType.ROUTE_CHARGE_JUNCTION, NodeType.PARKING_SLOT)),
                new ZoneSpec("STORAGE_ZONE", WarehouseZone.ZoneType.STORAGE, "랙 보관 구역",
                        List.of(NodeType.RACK_STORAGE, NodeType.RACK_ACCESS)),
                new ZoneSpec("INBOUND_ZONE", WarehouseZone.ZoneType.INBOUND, "입고 구역",
                        List.of(NodeType.INBOUND, NodeType.INBOUND_HANDOFF_ACCESS)),
                new ZoneSpec("OUTBOUND_ZONE", WarehouseZone.ZoneType.OUTBOUND, "출고 구역",
                        List.of(NodeType.OUTBOUND, NodeType.OUTBOUND_STATION_ACCESS,
                                NodeType.EMPTY_TOTE_BUFFER_ACCESS)),
                new ZoneSpec("CHARGING_ZONE", WarehouseZone.ZoneType.CHARGING, "충전 구역",
                        List.of(NodeType.CHARGING_SLOT))
        );

        List<WarehouseZone> zones = new ArrayList<>();

        for (ZoneSpec spec : specs) {
            List<WarehouseNode> members = nodes.stream()
                    .filter(node -> spec.members().contains(node.getNodeType()))
                    .filter(node -> node.getX() != null && node.getY() != null)
                    .toList();

            if (members.isEmpty()) {
                continue;
            }

            zones.add(WarehouseZone.create(
                    warehouse,
                    spec.name(),
                    spec.type(),
                    spec.description(),
                    members.stream().mapToDouble(WarehouseNode::getX).min().orElse(0),
                    members.stream().mapToDouble(WarehouseNode::getX).max().orElse(0),
                    members.stream().mapToDouble(WarehouseNode::getY).min().orElse(0),
                    members.stream().mapToDouble(WarehouseNode::getY).max().orElse(0)
            ));
        }

        warehouseZoneRepository.saveAll(zones);
    }

    private void createChargingStations(Warehouse warehouse, List<WarehouseNode> slots) {
        List<ChargingStation> stations = slots.stream()
                .map(node -> ChargingStation.create(
                        warehouse,
                        node,
                        "충전소 " + node.getNodeCode(),
                        ChargingStation.ChargingStationStatus.AVAILABLE,
                        DEFAULT_CHARGING_POWER
                ))
                .toList();

        chargingStationRepository.saveAll(stations);
    }

    private List<StorageLocation> createStorageLocations(Warehouse warehouse, List<WarehouseNode> racks) {
        LocalDateTime now = LocalDateTime.now();
        List<StorageLocation> locations = new ArrayList<>();

        for (WarehouseNode rack : racks) {
            StorageLocation location = new StorageLocation();
            location.setWarehouse(warehouse);
            location.setNode(rack);
            location.setMaxQuantity(100);
            location.setMaxWeight(1000);
            location.setMaxVolume(1000);
            location.setCreatedAt(now);
            location.setStatus("AVAILABLE");
            locations.add(location);
        }

        return storageLocationRepository.saveAll(locations);
    }

    private int createInitialInventory(Warehouse warehouse, List<StorageLocation> locations) {
        List<Product> products = productRepository.findAllByOrderByProductCodeAsc()
                .stream()
                .filter(product -> product.getUnitsPerBox() != null && product.getUnitsPerBox() > 0)
                .toList();

        if (products.isEmpty() || locations.isEmpty()) {
            log.warn("[창고 가져오기] 초기 재고 생략 - 품목 {}종, 보관위치 {}곳",
                    products.size(), locations.size());
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int stockedRacks = Math.max(1, locations.size() / 2);
        List<WarehouseItem> items = new ArrayList<>();

        for (int index = 0; index < stockedRacks; index++) {
            StorageLocation location = locations.get(index);

            for (int level = 1; level <= RACK_LEVELS; level++) {
                Product product = products.get((index * RACK_LEVELS + level - 1) % products.size());

                items.add(WarehouseItem.create(
                        warehouse,
                        location,
                        level,
                        location.getNode(),
                        product,
                        now,
                        product.getUnitsPerBox()
                ));
            }
        }

        warehouseItemRepository.saveAll(items);
        return items.size();
    }

    private int createRobots(Warehouse warehouse, List<WarehouseNode> slots, Integer requested) {
        if (slots.isEmpty()) {
            log.warn("[창고 가져오기] 충전 슬롯이 없어 로봇을 배치하지 못했습니다.");
            return 0;
        }

        RobotSpec spec = robotSpecRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));

        int count = Math.min(
                requested == null || requested <= 0 ? DEFAULT_ROBOT_COUNT : requested,
                slots.size()
        );

        List<WarehouseNode> orderedSlots = orderedChargingSlots(slots);
        List<Robot> robots = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            robots.add(Robot.create(
                    spec,
                    warehouse,
                    orderedSlots.get(index).getId(),
                    100,
                    RobotAvailabilityStatus.AVAILABLE
            ));
        }

        robotRepository.saveAll(robots);
        return robots.size();
    }

    private void synchronizeRobotHomeNodes(Long warehouseId, List<WarehouseNode> chargingSlots) {
        List<Robot> robots = robotRepository.findAllByWarehouse_Id(warehouseId).stream()
                .sorted(java.util.Comparator.comparing(Robot::getId))
                .toList();
        List<WarehouseNode> orderedSlots = orderedChargingSlots(chargingSlots);

        if (orderedSlots.size() < robots.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        for (int index = 0; index < robots.size(); index++) {
            robots.get(index).setNodeId(orderedSlots.get(index).getId());
        }
    }

    private List<WarehouseNode> orderedChargingSlots(List<WarehouseNode> chargingSlots) {
        if (chargingSlots.size() < 2) {
            return List.copyOf(chargingSlots);
        }
        double minX = chargingSlots.stream().mapToDouble(WarehouseNode::getX).min().orElse(0);
        double maxX = chargingSlots.stream().mapToDouble(WarehouseNode::getX).max().orElse(0);
        double minY = chargingSlots.stream().mapToDouble(WarehouseNode::getY).min().orElse(0);
        double maxY = chargingSlots.stream().mapToDouble(WarehouseNode::getY).max().orElse(0);
        java.util.Comparator<WarehouseNode> comparator = (maxX - minX) >= (maxY - minY)
                ? java.util.Comparator.comparingDouble(WarehouseNode::getX)
                        .thenComparingDouble(WarehouseNode::getY)
                : java.util.Comparator.comparingDouble(WarehouseNode::getY)
                        .thenComparingDouble(WarehouseNode::getX);
        return chargingSlots.stream()
                .sorted(comparator.thenComparing(WarehouseNode::getNodeCode))
                .toList();
    }

    private void createScenarioPresets(Warehouse warehouse, int robotCount) {
        int robots = Math.max(1, robotCount);

        List<Scenario> presets = List.of(
                Scenario.create(warehouse, "S1", "기본",
                        "표준 속도로 실행하는 기본 설정", robots, 100, 1.0, 20, true, false),
                Scenario.create(warehouse, "S2", "고속",
                        "2배속으로 빠르게 확인하는 설정", robots, 100, 2.0, 20, true, false),
                Scenario.create(warehouse, "S3", "장애물 포함",
                        "장애물이 발생하는 상황을 포함한 설정", robots, 100, 1.0, 20, true, true)
        );

        scenarioRepository.saveAll(presets);
    }

    /* =========================================================
       보조
    ========================================================= */

    private int[] resolveDimensions(WarehouseImportRequest request) {
        List<WarehouseImportRequest.MapNode> rawNodes = request.map() == null
                ? List.of()
                : request.map().nodes();

        double maxX = rawNodes.stream()
                .map(WarehouseImportRequest.MapNode::x)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(Double.NaN);
        double maxY = rawNodes.stream()
                .map(WarehouseImportRequest.MapNode::y)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(Double.NaN);

        if (Double.isNaN(maxX) || Double.isNaN(maxY)) {
            return new int[]{request.width(), request.height()};
        }

        return new int[]{
                Math.max(1, (int) Math.ceil(maxX) + 1),
                Math.max(1, (int) Math.ceil(maxY) + 1)
        };
    }

    private User findOwner(Long requestedUserId, Long loginUserId) {
        Long userId = requestedUserId != null ? requestedUserId : loginUserId;

        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
    }

    private List<WarehouseNode> nodesOf(List<WarehouseNode> nodes, NodeType type) {
        return nodes.stream()
                .filter(node -> node.getNodeType() == type)
                .sorted(Comparator.comparing(WarehouseNode::getNodeCode))
                .toList();
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    private WarehouseEdge.DirectionType directionOf(String value) {
        if (value == null || value.isBlank()) {
            return WarehouseEdge.DirectionType.BOTH;
        }
        return switch (value.trim().toUpperCase()) {
            case "BOTH", "BIDIRECTIONAL" -> WarehouseEdge.DirectionType.BOTH;
            case "B_TO_A", "REVERSE" -> WarehouseEdge.DirectionType.B_TO_A;
            default -> WarehouseEdge.DirectionType.A_TO_B;
        };
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
