package com.aivle.be.warehouseedge.entity;

import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "warehouse_edge")
@Getter
@Setter
@NoArgsConstructor
public class WarehouseEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "edge_id")
    private Long id;

    private Double distance;

    // 원본 ERD에 있던 중복 FK("노드id2") 컬럼은 제외했습니다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_node_id", nullable = false)
    private WarehouseNode fromNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_node_id", nullable = false)
    private WarehouseNode toNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_type", nullable = false)
    private DirectionType directionType;

    public enum DirectionType {
        BOTH, A_TO_B, B_TO_A
    }
}