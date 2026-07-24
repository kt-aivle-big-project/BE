package com.aivle.be.warehouseitem.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "warehouse_items")
@Getter
@NoArgsConstructor(access = PROTECTED)
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

    public static WarehouseItem create(
            Warehouse warehouse,
            StorageLocation storageLocation,
            WarehouseNode node,
            Long itemId,
            LocalDate expiryDate,
            LocalDateTime receivedAt,
            Integer quantity
    ) {
        WarehouseItem item = new WarehouseItem();
        item.warehouse = warehouse;
        item.storageLocation = storageLocation;
        item.node = node;
        item.itemId = itemId;
        item.expiryDate = expiryDate;
        item.receivedAt = receivedAt;
        item.quantity = quantity;
        item.inboundQuantity = 0;
        item.outboundQuantity = 0;
        return item;
    }

    public void update(
            StorageLocation storageLocation,
            WarehouseNode node,
            Long itemId,
            LocalDate expiryDate,
            Integer quantity
    ) {
        this.storageLocation = storageLocation;
        this.node = node;
        this.itemId = itemId;
        this.expiryDate = expiryDate;
        this.quantity = quantity;
    }

    public void increaseQuantity(int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        this.quantity = safeQuantity() + amount;
        this.inboundQuantity = safeCount(inboundQuantity) + amount;
    }

    public void decreaseQuantity(int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        int currentQuantity = safeQuantity();
        if (currentQuantity < amount) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        this.quantity = currentQuantity - amount;
        this.outboundQuantity = safeCount(outboundQuantity) + amount;
    }

    private int safeQuantity() {
        return quantity == null ? 0 : quantity;
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }
}
