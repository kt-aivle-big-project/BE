package com.aivle.be.warehouseedge.service;

import com.aivle.be.graph.event.WarehouseGraphChangedEvent;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeCreateRequest;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeResponse;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeUpdateRequest;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseEdgeService {

    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public WarehouseEdgeResponse createEdge(WarehouseEdgeCreateRequest request) {
        WarehouseNode fromNode = warehouseNodeRepository.findByIdAndActiveTrue(request.getFromNodeId())
                .orElseThrow(() -> new IllegalArgumentException("출발 노드를 찾을 수 없습니다."));

        WarehouseNode toNode = warehouseNodeRepository.findByIdAndActiveTrue(request.getToNodeId())
                .orElseThrow(() -> new IllegalArgumentException("도착 노드를 찾을 수 없습니다."));

        WarehouseEdge edge = WarehouseEdge.create(
                fromNode,
                toNode,
                request.getDistance(),
                request.getDirectionType(),
                request.getEdgeCode(),
                new WarehouseEdge.RouteProperties(
                        request.getEdgeType(),
                        request.getSpeedLimitMps(),
                        request.getNominalTravelTimeMs(),
                        request.getCost(),
                        request.getPhysicalResourceCode(),
                        request.getServiceOnly(),
                        request.getMobileRobotTraversable()
                ),
                request.getRouteAttributes()
        );

        WarehouseEdge saved = warehouseEdgeRepository.save(edge);
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(fromNode.getWarehouse().getId()));
        return WarehouseEdgeResponse.from(saved);
    }

    public WarehouseEdgeResponse getEdge(Long edgeId) {
        WarehouseEdge edge = warehouseEdgeRepository.findById(edgeId)
                .filter(value -> value.getFromNode().isActive() && value.getToNode().isActive())
                .orElseThrow(() -> new IllegalArgumentException("엣지를 찾을 수 없습니다."));

        return WarehouseEdgeResponse.from(edge);
    }

    public List<WarehouseEdgeResponse> getEdges() {
        return warehouseEdgeRepository.findAll()
                .stream()
                .filter(edge -> edge.getFromNode().isActive() && edge.getToNode().isActive())
                .map(WarehouseEdgeResponse::from)
                .toList();
    }

    @Transactional
    public WarehouseEdgeResponse updateEdge(
            Long edgeId,
            WarehouseEdgeUpdateRequest request
    ) {
        WarehouseEdge edge = warehouseEdgeRepository.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("엣지를 찾을 수 없습니다."));

        WarehouseNode fromNode = warehouseNodeRepository.findByIdAndActiveTrue(request.getFromNodeId())
                .orElseThrow(() -> new IllegalArgumentException("출발 노드를 찾을 수 없습니다."));

        WarehouseNode toNode = warehouseNodeRepository.findByIdAndActiveTrue(request.getToNodeId())
                .orElseThrow(() -> new IllegalArgumentException("도착 노드를 찾을 수 없습니다."));

        edge.update(
                fromNode,
                toNode,
                request.getDistance(),
                request.getDirectionType(),
                request.getEdgeCode(),
                new WarehouseEdge.RouteProperties(
                        request.getEdgeType(),
                        request.getSpeedLimitMps(),
                        request.getNominalTravelTimeMs(),
                        request.getCost(),
                        request.getPhysicalResourceCode(),
                        request.getServiceOnly(),
                        request.getMobileRobotTraversable()
                ),
                request.getRouteAttributes()
        );
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(fromNode.getWarehouse().getId()));

        return WarehouseEdgeResponse.from(edge);
    }

    @Transactional
    public void deleteEdge(Long edgeId) {
        WarehouseEdge edge = warehouseEdgeRepository.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("엣지를 찾을 수 없습니다."));

        Long warehouseId = edge.getFromNode().getWarehouse().getId();
        warehouseEdgeRepository.delete(edge);
        eventPublisher.publishEvent(new WarehouseGraphChangedEvent(warehouseId));
    }
}
