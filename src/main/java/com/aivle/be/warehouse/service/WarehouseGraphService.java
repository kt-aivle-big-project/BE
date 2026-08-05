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

/**
 * 창고 그래프(맵) 내보내기.
 *
 * 맵의 원본은 DB 이고, 프론트 화면과 AI(cuOpt/MAPF)가 이 API 로 같은 맵을 받아간다.
 * 각자 복사본을 들고 있으면 한쪽만 수정됐을 때 조용히 어긋나기 때문이다.
 */
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

    /**
     * 맵 버전 문자열을 만든다.
     *
     * 형식: MAP-{창고ID}-{노드수}-{간선수}-{체크섬}
     *
     * 노드·간선 구성이 조금이라도 바뀌면 체크섬이 달라진다.
     * 별도 관리 컬럼을 두면 갱신을 잊어 실제 맵과 어긋나므로 내용에서 직접 계산한다.
     */
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
