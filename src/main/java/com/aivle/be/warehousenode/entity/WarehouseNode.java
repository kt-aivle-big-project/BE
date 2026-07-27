package com.aivle.be.warehousenode.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.domain.NodeType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity
@Table(name = "warehouse_node")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "node_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "zone_id")
    private String zoneId;

    // 프론트 그래프의 노드 식별자 ("R0_0", "K0_1" 등)
    @Column(name = "node_code", length = 50)
    private String nodeCode;

    // 노드 역할 (통로 / 랙 / 입고구 / 출고구 / 충전 슬롯)
    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", length = 30)
    private NodeType nodeType;

    private Double x;

    private Double y;

    public static WarehouseNode create(
            Warehouse warehouse,
            String zoneId,
            Double x,
            Double y,
            String nodeCode,
            NodeType nodeType
    ) {
        WarehouseNode node = new WarehouseNode();
        node.warehouse = warehouse;
        node.zoneId = zoneId;
        node.x = x;
        node.y = y;
        node.nodeCode = nodeCode;
        node.nodeType = nodeType;
        return node;
    }

    public void update(
            String zoneId,
            Double x,
            Double y,
            String nodeCode,
            NodeType nodeType
    ) {
        this.zoneId = zoneId;
        this.x = x;
        this.y = y;
        if (nodeCode != null) {
            this.nodeCode = nodeCode;
        }
        if (nodeType != null) {
            this.nodeType = nodeType;
        }
    }

    public boolean isStorageNode() {
        return nodeType != null && nodeType.isStorage();
    }
}
