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
        WarehouseEdge edge = new WarehouseEdge();
        edge.fromNode = fromNode;
        edge.toNode = toNode;
        edge.distance = distance;
        edge.directionType = directionType;
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

    public enum DirectionType {
        BOTH,
        A_TO_B,
        B_TO_A
    }
}