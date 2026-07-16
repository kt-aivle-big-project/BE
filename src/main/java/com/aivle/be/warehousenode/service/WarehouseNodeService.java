package com.aivle.be.warehousenode.service;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.dto.WarehouseNodeCreateRequest;
import com.aivle.be.warehousenode.dto.WarehouseNodeResponse;
import com.aivle.be.warehousenode.dto.WarehouseNodeUpdateRequest;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class WarehouseNodeService {

    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseRepository warehouseRepository;

    public WarehouseNodeResponse createNode(WarehouseNodeCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("창고를 찾을 수 없습니다."));

        WarehouseNode node = WarehouseNode.create(
                warehouse,
                request.getZoneId(),
                request.getX(),
                request.getY()
        );

        WarehouseNode savedNode = warehouseNodeRepository.save(node);

        return WarehouseNodeResponse.from(savedNode);
    }

    @Transactional(readOnly = true)
    public WarehouseNodeResponse getNode(Long nodeId) {
        WarehouseNode node = warehouseNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        return WarehouseNodeResponse.from(node);
    }

    @Transactional(readOnly = true)
    public List<WarehouseNodeResponse> getNodes() {
        return warehouseNodeRepository.findAll()
                .stream()
                .map(WarehouseNodeResponse::from)
                .toList();
    }

    public WarehouseNodeResponse updateNode(
            Long nodeId,
            WarehouseNodeUpdateRequest request
    ) {
        WarehouseNode node = warehouseNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        node.update(
                request.getZoneId(),
                request.getX(),
                request.getY()
        );

        return WarehouseNodeResponse.from(node);
    }

    public void deleteNode(Long nodeId) {
        WarehouseNode node = warehouseNodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("노드를 찾을 수 없습니다."));

        warehouseNodeRepository.delete(node);
    }
}