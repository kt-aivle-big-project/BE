package com.aivle.be.warehouseedge.entity;

import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "warehouse_edge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "edge_id")
    private Long id;

    /**
     * 창고 그래프상의 간선 코드. (예: H0_0, V5_1, RA_K0_1_A)
     *
     * 프론트 warehouse_graph.json 및 AI(cuOpt/MAPF) 응답의 edge_id 와 대응한다.
     * 외부와 주고받을 때는 숫자 PK 대신 이 코드를 쓴다.
     */
    @Column(name = "edge_code", length = 50)
    private String edgeCode;

    private Double distance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_node_id", nullable = false)
    private WarehouseNode fromNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_node_id", nullable = false)
    private WarehouseNode toNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_type", nullable = false)
    private DirectionType directionType;

    public static WarehouseEdge create(
            WarehouseNode fromNode,
            WarehouseNode toNode,
            Double distance,
            DirectionType directionType
    ) {
        return create(fromNode, toNode, distance, directionType, null);
    }

    public static WarehouseEdge create(
            WarehouseNode fromNode,
            WarehouseNode toNode,
            Double distance,
            DirectionType directionType,
            String edgeCode
    ) {
        WarehouseEdge edge = new WarehouseEdge();
        edge.fromNode = fromNode;
        edge.toNode = toNode;
        edge.distance = distance;
        edge.directionType = directionType;
        edge.edgeCode = edgeCode;
        return edge;
    }

    public void update(
            WarehouseNode fromNode,
            WarehouseNode toNode,
            Double distance,
            DirectionType directionType
    ) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.distance = distance;
        this.directionType = directionType;
    }

    /**
     * 간선 코드를 지정한다. (그래프 동기화·마이그레이션용)
     */
    public void assignEdgeCode(String edgeCode) {
        this.edgeCode = edgeCode;
    }

    public enum DirectionType {
        BOTH,
        A_TO_B,
        B_TO_A
    }
}