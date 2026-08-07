package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.graph.event.WarehouseGraphChangedEvent;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WarehouseTemplateCloneService {

    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final WarehouseZoneRepository warehouseZoneRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final ChargingStationRepository chargingStationRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final RobotRepository robotRepository;
    private final ScenarioRepository scenarioRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Warehouse ensurePersonalCopy(
            Long templateWarehouseId,
            Long userId
    ) {
        Warehouse template = warehouseRepository.findById(templateWarehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        if (!template.isShared()) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_TEMPLATE);
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));

        return warehouseRepository
                .findByUser_IdAndSourceTemplate_Id(userId, templateWarehouseId)
                .orElseGet(() -> createPersonalCopy(
                        template,
                        Warehouse.createPersonalCopy(template, user)
                ));
    }

    @Transactional
    public Warehouse ensureGuestPersonalCopy(
            Long templateWarehouseId,
            String guestSessionId
    ) {
        if (guestSessionId == null || guestSessionId.isBlank()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        Warehouse template = warehouseRepository.findByIdForUpdate(templateWarehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        if (!template.isShared()) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_TEMPLATE);
        }

        return warehouseRepository
                .findByGuestSessionIdAndSourceTemplate_Id(
                        guestSessionId,
                        templateWarehouseId
                )
                .orElseGet(() -> createPersonalCopy(
                        template,
                        Warehouse.createGuestPersonalCopy(
                                template,
                                guestSessionId
                        )
                ));
    }

    private Warehouse createPersonalCopy(
            Warehouse template,
            Warehouse unsavedCopy
    ) {
        Warehouse copy = warehouseRepository.save(unsavedCopy);

        copyZones(template, copy);
        Map<Long, WarehouseNode> nodeMap = copyNodes(template, copy);
        copyEdges(template, nodeMap);
        copyChargingStations(template, copy, nodeMap);
        Map<Long, StorageLocation> storageMap = copyStorageLocations(
                template,
                copy,
                nodeMap
        );
        copyWarehouseItems(template, copy, nodeMap, storageMap);
        copyRobots(template, copy, nodeMap);
        copyScenarios(template, copy);

        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(copy.getId()));
        return copy;
    }

    private void copyZones(Warehouse template, Warehouse copy) {
        List<WarehouseZone> zones = warehouseZoneRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> WarehouseZone.create(
                        copy,
                        source.getName(),
                        source.getZoneType(),
                        source.getDescription(),
                        source.getMinX(),
                        source.getMaxX(),
                        source.getMinY(),
                        source.getMaxY()
                ))
                .toList();
        warehouseZoneRepository.saveAll(zones);
    }

    private Map<Long, WarehouseNode> copyNodes(Warehouse template, Warehouse copy) {
        Map<Long, WarehouseNode> nodeMap = new LinkedHashMap<>();
        List<WarehouseNode> nodes = warehouseNodeRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> {
                    WarehouseNode node = WarehouseNode.create(
                            copy,
                            source.getZoneId(),
                            source.getX(),
                            source.getY(),
                            source.getNodeCode(),
                            source.getNodeType(),
                            new WarehouseNode.RouteProperties(
                                    source.getServiceOnly(),
                                    source.getTransitAllowed(),
                                    source.getHoldingAllowed(),
                                    source.getNodeCapacity(),
                                    source.getResourceType(),
                                    source.getResourceCode(),
                                    source.getSide()
                            ),
                            source.getRouteAttributes()
                    );
                    if (!Boolean.TRUE.equals(source.getActive())) {
                        node.retire();
                    }
                    nodeMap.put(source.getId(), node);
                    return node;
                })
                .toList();

        warehouseNodeRepository.saveAll(nodes);
        warehouseNodeRepository.flush();
        return nodeMap;
    }

    private void copyEdges(
            Warehouse template,
            Map<Long, WarehouseNode> nodeMap
    ) {
        List<WarehouseEdge> edges = warehouseEdgeRepository
                .findAllByFromNode_Warehouse_Id(template.getId())
                .stream()
                .map(source -> WarehouseEdge.create(
                        requireNode(nodeMap, source.getFromNode().getId()),
                        requireNode(nodeMap, source.getToNode().getId()),
                        source.getDistance(),
                        source.getDirectionType(),
                        source.getEdgeCode(),
                        new WarehouseEdge.RouteProperties(
                                source.getEdgeType(),
                                source.getSpeedLimitMps(),
                                source.getNominalTravelTimeMs(),
                                source.getCost(),
                                source.getPhysicalResourceCode(),
                                source.getServiceOnly(),
                                source.getMobileRobotTraversable()
                        ),
                        source.getRouteAttributes()
                ))
                .toList();
        warehouseEdgeRepository.saveAll(edges);
    }

    private void copyChargingStations(
            Warehouse template,
            Warehouse copy,
            Map<Long, WarehouseNode> nodeMap
    ) {
        List<ChargingStation> stations = chargingStationRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> ChargingStation.create(
                        copy,
                        requireNode(nodeMap, source.getNode().getId()),
                        source.getName(),
                        source.getStatus(),
                        source.getChargingPower()
                ))
                .toList();
        chargingStationRepository.saveAll(stations);
    }

    private Map<Long, StorageLocation> copyStorageLocations(
            Warehouse template,
            Warehouse copy,
            Map<Long, WarehouseNode> nodeMap
    ) {
        Map<Long, StorageLocation> storageMap = new LinkedHashMap<>();
        List<StorageLocation> locations = storageLocationRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> {
                    StorageLocation location = new StorageLocation();
                    location.setWarehouse(copy);
                    location.setNode(requireNode(nodeMap, source.getNode().getId()));
                    location.setMaxQuantity(source.getMaxQuantity());
                    location.setMaxWeight(source.getMaxWeight());
                    location.setMaxVolume(source.getMaxVolume());
                    location.setCreatedAt(source.getCreatedAt());
                    location.setUpdatedAt(source.getUpdatedAt());
                    location.setStatus(source.getStatus());
                    storageMap.put(source.getId(), location);
                    return location;
                })
                .toList();
        storageLocationRepository.saveAll(locations);
        return storageMap;
    }

    private void copyWarehouseItems(
            Warehouse template,
            Warehouse copy,
            Map<Long, WarehouseNode> nodeMap,
            Map<Long, StorageLocation> storageMap
    ) {
        List<WarehouseItem> items = warehouseItemRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> WarehouseItem.copyTo(
                        copy,
                        requireStorage(storageMap, source.getStorageLocation().getId()),
                        requireNode(nodeMap, source.getNode().getId()),
                        source
                ))
                .toList();
        warehouseItemRepository.saveAll(items);
    }

    private void copyRobots(
            Warehouse template,
            Warehouse copy,
            Map<Long, WarehouseNode> nodeMap
    ) {
        List<Robot> robots = robotRepository
                .findAllByWarehouse_Id(template.getId())
                .stream()
                .map(source -> Robot.create(
                        source.getRobotSpec(),
                        copy,
                        mappedNodeId(nodeMap, source.getNodeId()),
                        source.getBattery(),
                        source.getStatus()
                ))
                .toList();
        robotRepository.saveAll(robots);
    }

    private void copyScenarios(Warehouse template, Warehouse copy) {
        List<Scenario> scenarios = scenarioRepository
                .findAllByWarehouse_IdOrderByIdAsc(template.getId())
                .stream()
                .map(source -> {
                    Scenario scenario = Scenario.create(
                            copy,
                            source.getScenarioCode(),
                            source.getScenarioName(),
                            source.getDescription(),
                            source.getRobotCount(),
                            source.getInitialBattery(),
                            source.getSimulationSpeed(),
                            source.getChargingThreshold(),
                            source.getAutoReplan(),
                            source.getObstacleEnabled()
                    );
                    scenario.updateTimings(
                            source.getMoveSecondsPerNode(),
                            source.getPickingSeconds(),
                            source.getLoadingSeconds()
                    );
                    return scenario;
                })
                .toList();
        scenarioRepository.saveAll(scenarios);
    }

    private WarehouseNode requireNode(
            Map<Long, WarehouseNode> nodeMap,
            Long sourceNodeId
    ) {
        WarehouseNode node = nodeMap.get(sourceNodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return node;
    }

    private StorageLocation requireStorage(
            Map<Long, StorageLocation> storageMap,
            Long sourceStorageId
    ) {
        StorageLocation storageLocation = storageMap.get(sourceStorageId);
        if (storageLocation == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return storageLocation;
    }

    private Long mappedNodeId(
            Map<Long, WarehouseNode> nodeMap,
            Long sourceNodeId
    ) {
        return sourceNodeId == null ? null : requireNode(nodeMap, sourceNodeId).getId();
    }
}
