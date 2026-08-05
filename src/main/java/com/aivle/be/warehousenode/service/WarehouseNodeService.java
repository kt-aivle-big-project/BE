package com.aivle.be.warehousenode.service;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.graph.event.WarehouseGraphChangedEvent;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.dto.WarehouseNodeCreateRequest;
import com.aivle.be.warehousenode.dto.WarehouseNodeResponse;
import com.aivle.be.warehousenode.dto.WarehouseNodeUpdateRequest;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class WarehouseNodeService {

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

    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ChargingStationRepository chargingStationRepository;
    private final RobotRepository robotRepository;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    public WarehouseNodeResponse createNode(WarehouseNodeCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("창고를 찾을 수 없습니다."));

        WarehouseNode node = WarehouseNode.create(
                warehouse,
                request.getZoneId(),
                request.getX(),
                request.getY(),
                request.getNodeCode(),
                request.getNodeType(),
                new WarehouseNode.RouteProperties(
                        request.getServiceOnly(),
                        request.getTransitAllowed(),
                        request.getHoldingAllowed(),
                        request.getNodeCapacity(),
                        request.getResourceType(),
                        request.getResourceCode(),
                        request.getSide()
                ),
                request.getRouteAttributes()
        );

        WarehouseNode savedNode = warehouseNodeRepository.save(node);
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(warehouse.getId()));

        return WarehouseNodeResponse.from(savedNode);
    }

    @Transactional(readOnly = true)
    public WarehouseNodeResponse getNode(Long nodeId) {
        WarehouseNode node = warehouseNodeRepository.findByIdAndActiveTrue(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        return WarehouseNodeResponse.from(node);
    }

    @Transactional(readOnly = true)
    public List<WarehouseNodeResponse> getNodes() {
        return warehouseNodeRepository.findAll().stream()
                .filter(WarehouseNode::isActive)
                .map(WarehouseNodeResponse::from)
                .toList();
    }

    public WarehouseNodeResponse updateNode(
            Long nodeId,
            WarehouseNodeUpdateRequest request
    ) {
        WarehouseNode node = warehouseNodeRepository.findByIdAndActiveTrue(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        node.update(
                request.getZoneId(),
                request.getX(),
                request.getY(),
                request.getNodeCode(),
                request.getNodeType(),
                new WarehouseNode.RouteProperties(
                        request.getServiceOnly(),
                        request.getTransitAllowed(),
                        request.getHoldingAllowed(),
                        request.getNodeCapacity(),
                        request.getResourceType(),
                        request.getResourceCode(),
                        request.getSide()
                ),
                request.getRouteAttributes()
        );
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(node.getWarehouse().getId()));

        return WarehouseNodeResponse.from(node);
    }

    public void deleteNode(Long nodeId) {
        WarehouseNode node = warehouseNodeRepository.findByIdAndActiveTrue(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        Long warehouseId = node.getWarehouse().getId();
        boolean referencedByResource = storageLocationRepository
                .findAllByWarehouse_Id(warehouseId).stream()
                .anyMatch(value -> value.getNode().getId().equals(nodeId))
                || chargingStationRepository.findAllByWarehouse_Id(warehouseId).stream()
                .anyMatch(value -> value.getNode().getId().equals(nodeId))
                || robotRepository.findAllByWarehouse_Id(warehouseId).stream()
                .anyMatch(value -> nodeId.equals(value.getNodeId()));
        if (referencedByResource) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (taskRepository.countOperationalReferences(
                warehouseId,
                Set.of(nodeId),
                OPERATIONAL_TASK_STATUSES,
                OPERATIONAL_RUN_STATUSES
        ) > 0) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NODE_IN_ACTIVE_TASK);
        }

        warehouseEdgeRepository.deleteAll(
                warehouseEdgeRepository.findAllByFromNode_IdOrToNode_Id(nodeId, nodeId)
        );
        node.retire();
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(warehouseId));
    }
}
