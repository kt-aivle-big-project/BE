package com.aivle.be.warehouse.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.warehouse.dto.WarehouseGraphResponse;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;

@Service
@RequiredArgsConstructor
public class WarehouseGraphService {

    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;

    @Transactional(readOnly = true)
    public WarehouseGraphResponse getGraph(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        List<WarehouseNode> nodes = warehouseNodeRepository
                .findAllByWarehouse_IdAndActiveTrue(warehouseId)
                .stream()
                .sorted(Comparator.comparing(WarehouseNode::getId))
                .toList();

        List<WarehouseEdge> edges = warehouseEdgeRepository
                .findAllActiveByWarehouseId(warehouseId)
                .stream()
                .sorted(Comparator.comparing(WarehouseEdge::getId))
                .toList();

        return new WarehouseGraphResponse(
                warehouse.getId(),
                warehouse.getName(),
                buildMapVersion(warehouseId, nodes, edges),
                nodes.size(),
                edges.size(),
                nodes.stream().map(WarehouseGraphResponse.GraphNode::from).toList(),
                edges.stream().map(WarehouseGraphResponse.GraphEdge::from).toList()
        );
    }

    private String buildMapVersion(
            Long warehouseId,
            List<WarehouseNode> nodes,
            List<WarehouseEdge> edges
    ) {
        StringBuilder signature = new StringBuilder();

        for (WarehouseNode node : nodes) {
            signature.append(node.getNodeCode()).append(':')
                    .append(node.getNodeType()).append(':')
                    .append(node.getX()).append(',')
                    .append(node.getY()).append(':')
                    .append(node.getServiceOnly()).append(':')
                    .append(node.getTransitAllowed()).append(':')
                    .append(node.getHoldingAllowed()).append(':')
                    .append(node.getNodeCapacity()).append(':')
                    .append(node.getResourceType()).append(':')
                    .append(node.getResourceCode()).append(':')
                    .append(node.getSide()).append(':')
                    .append(sortedAttributes(node.getRouteAttributes())).append('|');
        }

        for (WarehouseEdge edge : edges) {
            signature.append(edge.getEdgeCode()).append(':')
                    .append(edge.getFromNode().getNodeCode()).append('>')
                    .append(edge.getToNode().getNodeCode()).append(':')
                    .append(edge.getDirectionType()).append(':')
                    .append(edge.getEdgeType()).append(':')
                    .append(edge.getSpeedLimitMps()).append(':')
                    .append(edge.getNominalTravelTimeMs()).append(':')
                    .append(edge.getCost()).append(':')
                    .append(edge.getPhysicalResourceCode()).append(':')
                    .append(edge.getServiceOnly()).append(':')
                    .append(edge.getMobileRobotTraversable()).append(':')
                    .append(sortedAttributes(edge.getRouteAttributes())).append('|');
        }

        CRC32 checksum = new CRC32();
        checksum.update(signature.toString().getBytes());

        return String.format(
                "MAP-%d-%d-%d-%08x",
                warehouseId, nodes.size(), edges.size(), checksum.getValue()
        );
    }

    private Object sortedAttributes(java.util.Map<String, Object> attributes) {
        return attributes == null ? "{}" : new TreeMap<>(attributes);
    }
}
