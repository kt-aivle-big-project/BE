package com.aivle.be.storagelocation.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "storage_location")
@Getter
@Setter
@NoArgsConstructor
public class StorageLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "storage_location_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false, unique = true)
    private WarehouseNode node;

    @Column(name = "max_quantity")
    private Integer maxQuantity;

    @Column(name = "max_weight")
    private Integer maxWeight;

    @Column(name = "max_volume")
    private Integer maxVolume;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private String status;
}
