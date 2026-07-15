package com.aivle.be.warehousenode.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "warehouse_node")
@Getter
@Setter
@NoArgsConstructor
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

    private Double x;

    private Double y;

    public static WarehouseNode create(
            Warehouse warehouse,
            String zoneId,
            Double x,
            Double y
    ) {
        WarehouseNode node = new WarehouseNode();
        node.warehouse = warehouse;
        node.zoneId = zoneId;
        node.x = x;
        node.y = y;
        return node;
    }

    public void update(
            String zoneId,
            Double x,
            Double y
    ) {
        this.zoneId = zoneId;
        this.x = x;
        this.y = y;
    }
}