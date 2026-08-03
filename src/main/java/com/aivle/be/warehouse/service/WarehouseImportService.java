package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.graph.service.AiRouteGraphSyncService;
import com.aivle.be.optimization.service.AiPostgresContractSyncService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
 * <p>기본 창고 3개도 {@link DefaultWarehouseSeeder} 를 통해 이 서비스로 만들어진다.
 * 지도를 다루는 경로는 여기 하나뿐이다.
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
     * <p>랙 접근 자리처럼 하나로 합칠 수는 없다.
     * 랙은 접근 자리 2개가 랙 1개에 대응하지만,
     * 출고 진입 자리 {@code O_0} 은 출고구 {@code O_A}·{@code O_B}·{@code O_C} 세 개에 동시에 붙어 있다.
     * 즉 이 자리들은 합칠 대상이 아니라 그냥 통로 교차점이다.
     */
    private static final Map<String, NodeType> NODE_TYPES = Map.of(
            "route", NodeType.ROUTE,
            "route_charge_junction", NodeType.ROUTE_CHARGE_JUNCTION,
            "inbound", NodeType.INBOUND,
            "outbound", NodeType.OUTBOUND,
            "charging_slot", NodeType.CHARGING_SLOT,
            "inbound_access", NodeType.ROUTE,
            "outbound_access", NodeType.ROUTE
    );

    /** 노드 타입 -> 구역 이름 */
    private static final Map<NodeType, String> ZONE_NAMES = Map.of(
            NodeType.ROUTE, "MOVING_ZONE",
            NodeType.ROUTE_CHARGE_JUNCTION, "MOVING_ZONE",
            NodeType.RACK_STORAGE, "STORAGE_ZONE",
            NodeType.INBOUND, "INBOUND_ZONE",
            NodeType.OUTBOUND, "OUTBOUND_ZONE",
            NodeType.CHARGING_SLOT, "CHARGING_ZONE"
    );

    private static final String RACK_ACCESS_TYPE = "rack_access";
    private static final String ACCESS_MARKER = "_ACCESS";

    private static final int DEFAULT_ROBOT_COUNT = 6;
    private static final double DEFAULT_CHARGING_POWER = 50.0;

    /** 초기 재고를 넣을 랙 수와 랙당 수량. 기본 창고 3개의 시드와 같은 값이다. */
    private static final int INITIAL_STOCK_LOCATIONS = 10;
    private static final int INITIAL_STOCK_QUANTITY = 50;

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
    private final JdbcTemplate jdbcTemplate;
    private final AiRouteGraphSyncService aiRouteGraphSyncService;
    private final AiPostgresContractSyncService aiPostgresContractSyncService;

    /** 화면에서 지도를 올려 창고를 만든다. 항상 개인 창고다. */
    @Transactional
    public WarehouseImportResponse importWarehouse(WarehouseImportRequest request, Long loginUserId) {
        return importWarehouse(request, loginUserId, false, null);
    }

    /**
     * 지도로 창고를 만든다.
     *
     * @param shared  공용 창고로 표시할지. 앱 시작 시 넣는 기본 창고에만 true.
     * @param fixedId 창고 ID 를 못 박고 싶을 때. null 이면 자동 부여.
     *                기본 창고는 화면과 저장된 선택값이 ID 를 기준으로 하므로 1·2·3 으로 고정한다.
     */
    @Transactional
    public WarehouseImportResponse importWarehouse(
            WarehouseImportRequest request,
            Long loginUserId,
            boolean shared,
            Long fixedId
    ) {
        User owner = findOwner(request.userId(), loginUserId);
        Warehouse warehouse = createWarehouse(request, owner, fixedId);

        if (shared) {
            warehouse.markShared();
        }

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
        List<WarehouseNode> chargingJunctions =
                nodesOf(nodes, NodeType.ROUTE_CHARGE_JUNCTION);
        List<WarehouseNode> racks = nodesOf(nodes, NodeType.RACK_STORAGE);

        createChargingStations(warehouse, chargingSlots);
        List<StorageLocation> locations = createStorageLocations(warehouse, racks);
        int stockedCount = createInitialInventory(warehouse, locations);
        // Charging slots are terminal one-way destinations in the AI route
        // graph. A robot initialized there cannot leave the slot. Start robots
        // on the adjacent route/charge junctions instead.
        List<WarehouseNode> robotStartNodes = chargingJunctions.isEmpty()
                ? chargingSlots
                : chargingJunctions;
        int robotCount = createRobots(
                warehouse,
                robotStartNodes,
                request.robotCount()
        );
        createScenarioPresets(warehouse, robotCount);

        int skipped = request.map().nodes().size() - nodes.size();
        String aiWarehouseId = aiRouteGraphSyncService.sync(
                warehouse.getId(),
                request.map()
        );
        aiPostgresContractSyncService.syncImportedWarehouse(
                warehouse.getId(),
                warehouse.getName(),
                request.map()
        );

        log.info("[창고 가져오기] {} (id={}) 노드 {}, 간선 {}, 랙 {}, 충전소 {}, 로봇 {}, 초기 재고 {}곳 (제외 {})",
                warehouse.getName(), warehouse.getId(),
                nodes.size(), edgeCount, racks.size(), chargingSlots.size(),
                robotCount, stockedCount, skipped);

        return new WarehouseImportResponse(
                warehouse.getId(),
                aiWarehouseId,
                warehouse.getName(),
                nodes.size(),
                edgeCount,
                racks.size(),
                chargingSlots.size(),
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
                    nodeType
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
                    NodeType.RACK_STORAGE
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
        Map<String, String> accessToRack = new LinkedHashMap<>();

        for (WarehouseImportRequest.MapNode raw : map.nodes()) {
            if (RACK_ACCESS_TYPE.equals(lower(raw.type()))) {
                String rackCode = rackCodeOf(raw);
                if (rackCode != null) {
                    accessToRack.put(raw.id(), rackCode);
                }
            }
        }

        // 방향별로 모은다
        Map<String, WarehouseImportRequest.MapEdge> directed = new LinkedHashMap<>();

        for (WarehouseImportRequest.MapEdge raw : map.edges()) {
            String source = resolve(raw.source(), nodeByCode, accessToRack);
            String target = resolve(raw.target(), nodeByCode, accessToRack);

            if (source == null || target == null || source.equals(target)) {
                continue;
            }

            directed.putIfAbsent(source + ">" + target, raw);
        }

        List<WarehouseEdge> edges = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();

        for (Map.Entry<String, WarehouseImportRequest.MapEdge> entry : directed.entrySet()) {
            String[] pair = entry.getKey().split(">", 2);
            String source = pair[0];
            String target = pair[1];

            if (used.contains(source + ">" + target) || used.contains(target + ">" + source)) {
                continue;
            }

            used.add(source + ">" + target);

            boolean twoWay = directed.containsKey(target + ">" + source);
            WarehouseImportRequest.MapEdge raw = entry.getValue();

            edges.add(WarehouseEdge.create(
                    nodeByCode.get(source),
                    nodeByCode.get(target),
                    raw.distance_m() == null ? 1.0 : raw.distance_m(),
                    twoWay ? WarehouseEdge.DirectionType.BOTH : WarehouseEdge.DirectionType.A_TO_B,
                    edgeCode(raw.id(), twoWay)
            ));
        }

        warehouseEdgeRepository.saveAll(edges);
        return edges.size();
    }

    /**
     * 간선이 가리키는 코드를 저장 대상 노드 코드로 바꾼다.
     * 저장하지 않는 자리(입출고 접근 등)로 가는 간선은 버린다.
     */
    private String resolve(
            String code,
            Map<String, WarehouseNode> nodeByCode,
            Map<String, String> accessToRack
    ) {
        if (nodeByCode.containsKey(code)) {
            return code;
        }

        String rackCode = accessToRack.get(code);
        return rackCode != null && nodeByCode.containsKey(rackCode) ? rackCode : null;
    }

    /** 왕복으로 합쳐진 간선은 방향 접미사를 뗀다. RA_K0_1_A_IN -> RA_K0_1_A */
    private String edgeCode(String code, boolean twoWay) {
        if (code == null || !twoWay) {
            return code;
        }
        if (code.endsWith("_IN")) {
            return code.substring(0, code.length() - 3);
        }
        if (code.endsWith("_OUT")) {
            return code.substring(0, code.length() - 4);
        }
        return code;
    }

    /* =========================================================
       구역 · 설비
    ========================================================= */

    private void createZones(Warehouse warehouse, List<WarehouseNode> nodes) {
        record ZoneSpec(String name, WarehouseZone.ZoneType type, String description, List<NodeType> members) {}

        List<ZoneSpec> specs = List.of(
                new ZoneSpec("MOVING_ZONE", WarehouseZone.ZoneType.MOVING, "이동 통로",
                        List.of(NodeType.ROUTE, NodeType.ROUTE_CHARGE_JUNCTION)),
                new ZoneSpec("STORAGE_ZONE", WarehouseZone.ZoneType.STORAGE, "랙 보관 구역",
                        List.of(NodeType.RACK_STORAGE)),
                new ZoneSpec("INBOUND_ZONE", WarehouseZone.ZoneType.INBOUND, "입고 구역",
                        List.of(NodeType.INBOUND)),
                new ZoneSpec("OUTBOUND_ZONE", WarehouseZone.ZoneType.OUTBOUND, "출고 구역",
                        List.of(NodeType.OUTBOUND)),
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
     * 앞쪽 랙 몇 곳에 초기 재고를 넣는다.
     *
     * <p>출고 작업은 재고가 있는 랙에서만 만들어진다
     * ({@code ScenarioTaskPlanner.planOutbound}).
     * 재고가 하나도 없으면 출고 30건을 요청해도 0건이 나오고
     * 입고 작업만 남는다. 그래서 창고를 만들 때 씨앗 재고를 같이 넣는다.
     *
     * <p>랙 10곳에 품목을 돌아가며 50개씩 넣는다.
     * 랙 10곳에 품목을 돌아가며 50개씩.
     */
    private int createInitialInventory(Warehouse warehouse, List<StorageLocation> locations) {
        List<Long> productIds = productRepository.findAllByOrderByProductCodeAsc()
                .stream()
                .map(Product::getId)
                .toList();

        if (productIds.isEmpty() || locations.isEmpty()) {
            log.warn("[창고 가져오기] 초기 재고 생략 - 품목 {}종, 보관위치 {}곳",
                    productIds.size(), locations.size());
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int count = Math.min(INITIAL_STOCK_LOCATIONS, locations.size());

        List<WarehouseItem> items = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            StorageLocation location = locations.get(index);

            items.add(WarehouseItem.create(
                    warehouse,
                    location,
                    location.getNode(),
                    productIds.get(index % productIds.size()),
                    null,
                    now,
                    INITIAL_STOCK_QUANTITY
            ));
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

        List<Robot> robots = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            robots.add(Robot.create(
                    spec,
                    warehouse,
                    slots.get(index).getId(),
                    100,
                    RobotAvailabilityStatus.AVAILABLE
            ));
        }

        robotRepository.saveAll(robots);
        return robots.size();
    }

    /** 화면에서 고를 수 있는 실행 설정을 만들어 둔다. */
    private void createScenarioPresets(Warehouse warehouse, int robotCount) {
        int robots = Math.max(1, robotCount);

        List<Scenario> presets = List.of(
                Scenario.create(warehouse, "S1", "기본", robots, 1.0, 20, true, false),
                Scenario.create(warehouse, "S2", "고속", robots, 2.0, 20, true, false),
                Scenario.create(warehouse, "S3", "장애물 포함", robots, 1.0, 20, true, true)
        );

        scenarioRepository.saveAll(presets);
    }

    /* =========================================================
       보조
    ========================================================= */

    /**
     * 창고 행을 만든다.
     *
     * <p>ID 를 지정한 경우에는 JPA 가 아니라 SQL 로 직접 넣는다.
     * 기본키가 자동 증가라 엔티티로는 ID 를 정할 수 없기 때문이다.
     * 넣은 뒤에는 자동 증가 값을 최대 ID 뒤로 밀어 다음 창고와 겹치지 않게 한다.
     */
    private Warehouse createWarehouse(
            WarehouseImportRequest request,
            User owner,
            Long fixedId
    ) {
        if (fixedId == null) {
            return warehouseRepository.save(
                    Warehouse.create(
                            request.name(),
                            request.width(),
                            request.height(),
                            owner,
                            request.location(),
                            request.description(),
                            request.status()
                    )
            );
        }

        Warehouse.WarehouseStatus status = request.status() == null
                ? Warehouse.WarehouseStatus.ACTIVE
                : request.status();

        jdbcTemplate.update("""
                        INSERT INTO warehouse_layout
                            (id, name, width, height, user_id, location, description,
                             status, is_shared, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, false, NOW(), NOW())
                        """,
                fixedId, request.name(), request.width(), request.height(),
                owner.getId(), request.location(), request.description(), status.name());

        jdbcTemplate.execute("""
                SELECT setval(
                    pg_get_serial_sequence('warehouse_layout', 'id'),
                    GREATEST((SELECT COALESCE(MAX(id), 1) FROM warehouse_layout), 1),
                    true)
                """);

        return warehouseRepository.findById(fixedId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
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

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
