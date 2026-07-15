package com.aivle.be.warehouseitem.entity;

import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "warehouse_items")
@Getter
@Setter
@NoArgsConstructor
public class WarehouseItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "warehouse_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storage_location_id", nullable = false)
    private StorageLocation storageLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false)
    private WarehouseNode node;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "inbound_quantity")
    private Integer inboundQuantity;

    @Column(name = "outbound_quantity")
    private Integer outboundQuantity;
}