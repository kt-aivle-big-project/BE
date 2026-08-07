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

/**
 * 지도 JSON 을 읽어 창고 하나를 통째로 만든다.
 *
 * <p>화면에서 올린 warehouse_graph.json 은 AI 방식으로 되어 있어
 * 우리 구조와 두 가지가 다르다.
 *
 * <pre>
 *   1. 랙 자체가 노드로 없다
 *      K0_1 은 없고 K0_1_ACCESS_A / _B 만 있다.
 *      우리는 재고를 노드에 붙이므로 랙 노드가 있어야 한다.
 *
 *   2. 왕복 통로가 두 줄로 적혀 있다
 *      RA_K0_1_A_IN 과 RA_K0_1_A_OUT 처럼 방향마다 한 줄씩.
 *      그대로 넣으면 같은 길이 두 개가 되어 경로 계산이 흔들린다.
 * </pre>
 *
 * <p>그래서 저장 전에 이렇게 바꾼다.
 *
 * <pre>
 *   접근 노드 이름 -> 랙 노드 생성    K0_1_ACCESS_A -> K0_1
 *   좌표는 양쪽 접근 자리의 가운데
 *   접근 자리를 거치던 간선은 랙에 직접 연결
 *   왕복 두 줄은 BOTH 한 줄로 합침
 * </pre>
 *
 * <p>이 변환은 {@code tools/generate_warehouse_seed.py} 와 같은 규칙이다.
 * 기본 창고 3개는 그 스크립트로 미리 만들고, 사용자가 추가하는 창고는 여기서 만든다.
 */
@Service
@RequiredArgsConstructor
public class WarehouseImportService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseImportService.class);

    /**
     * 지도 JSON 의 타입 -> 우리 노드 타입.
     *
     * <p>{@code inbound_access} / {@code outbound_access} 는 입출고구 앞의 진입 자리다.
     * 통로에서 입고구로 가는 길이 전부 이 자리를 거치므로 반드시 저장해야 한다.
     * 빠뜨리면 양 끝 중 한쪽이 없는 간선이 통째로 버려져
     * 입고구·출고구가 통로와 끊긴 외딴섬이 된다.
     *
     * <p>랙 접근 자리처럼 설비 하나로 합칠 수는 없다.
     * 랙은 접근 자리 2개가 랙 1개에 대응하지만,
     * 출고 진입 자리 {@code O_0} 은 출고구 {@code O_A}·{@code O_B}·{@code O_C}
     * 세 개에 동시에 붙어 있다. 즉 이 자리들은 설비가 아니라 통로 교차점이므로
     * {@code ROUTE} 로 저장한다.
     */
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

    /** 선반 한 대의 층 수. WarehouseItem 이 1~3만 허용한다. */
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
        createScenarioPresets(warehouse, robotCount);

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

    /**
     * Reconcile an edited map with an existing warehouse.
     *
     * <p>Stable node/edge codes are used as identities, so moving an icon
     * updates coordinates without breaking inventory, charging-station or
     * robot foreign keys. A protected rack/charging node cannot be removed
     * while business data still references it.</p>
     */
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
                // 모르는 종류의 노드는 저장하지 않는다.
                // 그 노드에 붙은 간선까지 같이 사라지므로 이름을 남긴다.
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
                // 끝점 노드를 못 찾은 간선은 저장되지 않는다.
                // 조용히 버리면 "저장은 되는데 간선만 안 생긴다" 로 보이므로
                // 어느 노드 이름이 어긋났는지 남긴다.
                droppedEdges.add("%s(%s->%s)".formatted(
                        raw.id(), raw.source(), raw.target()));
                continue;
            }
            String edgeCode = raw.id();
            if (edgeCode == null || edgeCode.isBlank()) {
                edgeCode = raw.source() + "::" + raw.target();
            }
            if (!requestedEdgeCodes.add(edgeCode)) {
                // 같은 코드를 두 번 보내면 뒤에 온 간선은 저장되지 않는다.
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
        // while AI still reads the old seeded station rows.
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

    /**
     * 저장할 노드를 만든다.
     *
     * <p>통로·충전·입출고는 그대로 옮기고,
     * 랙 접근 자리는 저장하지 않는 대신 그 이름에서 랙 노드를 만들어낸다.
     */
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

        // 랙 되살리기 — 접근 자리 좌표의 가운데를 랙 위치로 본다
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

    /**
     * 접근 자리가 가리키는 랙 코드.
     * rack_id 가 있으면 그걸 쓰고, 없으면 이름에서 잘라낸다.
     */
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

    /**
     * 간선을 만든다.
     *
     * <p>접근 자리를 거치던 간선은 랙에 직접 잇는다.
     * <pre>
     *   R0_1 -> K0_1_ACCESS_A   =>   R0_1 -> K0_1
     * </pre>
     *
     * <p>양방향이 두 줄로 온 경우 BOTH 한 줄로 합친다.
     */
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

    /**
     * 간선이 가리키는 코드를 저장 대상 노드 코드로 바꾼다.
     * 저장하지 않는 자리(입출고 접근 등)로 가는 간선은 버린다.
     */
    /** 왕복으로 합쳐진 간선은 방향 접미사를 뗀다. RA_K0_1_A_IN -> RA_K0_1_A */
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

    /** 랙마다 보관 자리를 하나씩 만든다. 재고는 여기에 붙는다. */
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

    /**
     * 새로 만든 창고의 랙 앞쪽 절반을 3층까지 채운다.
     *
     * <p>기본 창고는 {@code V05_inventory.sql} 이 재고를 깔아 주지만,
     * 지도를 올려 만든 창고에는 그 시드가 없다. 재고가 0이면
     *
     * <pre>
     *   화면 - 선반 3칸이 전부 빈칸으로만 보인다
     *   실행 - 출고 작업이 하나도 만들어지지 않는다
     * </pre>
     *
     * <p>재고는 BOX 단위라 수량은 품목의 {@code unitsPerBox} 를 그대로 쓴다.
     * 뒤쪽 절반은 비워 둬야 입고 작업이 들어갈 자리가 생긴다.
     *
     * @return 채운 칸 수 (랙 수 × 층 수)
     */
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

    /** 로봇은 충전 슬롯에서 시작한다. */
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

    /**
     * Keep one deterministic charging home per robot after a map edit.
     * Runtime position lives in Redis, so updating this master node does not
     * teleport a running robot; it only changes the terminal node used by the
     * next plan/replan.
     */
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

    /** 화면에서 고를 수 있는 실행 설정을 만들어 둔다. */
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

    /**
     * 창고의 가로·세로를 지도 좌표에서 정한다.
     *
     * <p>지도 JSON 의 좌표는 파워포인트 인치라 사용자가 입력한 폭·높이와
     * 아무 관계가 없다. 입력값을 그대로 쓰면 화면의 도면 영역이
     * 노드가 실제로 차지하는 범위와 어긋나 도면이 잘리거나 구석에 작게 박힌다.
     *
     * <p>그래서 노드 좌표의 최댓값을 올림해 창고 크기로 삼는다.
     * 좌표를 읽을 수 없을 때만 요청값으로 되돌아간다.
     *
     * @return {가로, 세로}
     */
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

        // 가장자리 노드가 경계선에 딱 붙지 않도록 한 칸 여유를 준다.
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
            // 사용자가 그린 일반 연결선의 기본 계약은 왕복 통행이다.
            // 단방향이 필요한 서비스 인계선은 요청에 A_TO_B/B_TO_A를 명시한다.
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
