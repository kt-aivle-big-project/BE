package com.aivle.be.warehouseedge.service;

import com.aivle.be.warehouseedge.dto.WarehouseEdgeCreateRequest;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeResponse;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeUpdateRequest;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseEdgeService {

    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;

    @Transactional
    public WarehouseEdgeResponse createEdge(WarehouseEdgeCreateRequest request) {
        WarehouseNode fromNode = warehouseNodeRepository.findById(request.getFromNodeId())
                .orElseThrow(() -> new IllegalArgumentException("출발 노드를 찾을 수 없습니다."));

        WarehouseNode toNode = warehouseNodeRepository.findById(request.getToNodeId())
                .orElseThrow(() -> new IllegalArgumentException("도착 노드를 찾을 수 없습니다."));

        WarehouseEdge edge = WarehouseEdge.create(
                fromNode,
                toNode,
                request.getDistance(),
                request.getDirectionType()
        );

        return WarehouseEdgeResponse.from(warehouseEdgeRepository.save(edge));
    }

    public WarehouseEdgeResponse getEdge(Long edgeId) {
        WarehouseEdge edge = warehouseEdgeRepository.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("엣지를 찾을 수 없습니다."));

        return WarehouseEdgeResponse.from(edge);
    }

    public List<WarehouseEdgeResponse> getEdges() {
        return warehouseEdgeRepository.findAll()
                .stream()
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

        WarehouseNode fromNode = warehouseNodeRepository.findById(request.getFromNodeId())
                .orElseThrow(() -> new IllegalArgumentException("출발 노드를 찾을 수 없습니다."));

        WarehouseNode toNode = warehouseNodeRepository.findById(request.getToNodeId())
                .orElseThrow(() -> new IllegalArgumentException("도착 노드를 찾을 수 없습니다."));

        edge.update(
                fromNode,
                toNode,
                request.getDistance(),
                request.getDirectionType()
        );

        return WarehouseEdgeResponse.from(edge);
    }

    @Transactional
    public void deleteEdge(Long edgeId) {
        WarehouseEdge edge = warehouseEdgeRepository.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("엣지를 찾을 수 없습니다."));

        warehouseEdgeRepository.delete(edge);
    }
}