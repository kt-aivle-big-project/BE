package com.aivle.be.warehouseitem.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.entity.Product;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(
        name = "warehouse_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_warehouse_items_storage_location_level",
                columnNames = {"storage_location_id", "rack_level"}
        )
)
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

    /** One physical rack has exactly three levels; each level stores one BOX. */
    @Column(name = "rack_level", nullable = false, columnDefinition = "integer default 1")
    private Integer rackLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false)
    private WarehouseNode node;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_warehouse_items_product")
    )
    private Product product;

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
            Integer rackLevel,
            WarehouseNode node,
            Product product,
            LocalDateTime receivedAt,
            Integer quantity
    ) {
        requireValidBoxQuantity(product, quantity);
        requireValidRackLevel(rackLevel);
        WarehouseItem item = new WarehouseItem();
        item.warehouse = warehouse;
        item.storageLocation = storageLocation;
        item.rackLevel = rackLevel;
        item.node = node;
        item.product = product;
        item.receivedAt = receivedAt;
        item.quantity = quantity;
        item.inboundQuantity = 0;
        item.outboundQuantity = 0;
        return item;
    }

    public void update(
            StorageLocation storageLocation,
            Integer rackLevel,
            WarehouseNode node,
            Product product,
            Integer quantity
    ) {
        requireValidBoxQuantity(product, quantity);
        requireValidRackLevel(rackLevel);
        this.storageLocation = storageLocation;
        this.rackLevel = rackLevel;
        this.node = node;
        this.product = product;
        this.quantity = quantity;
    }

    /** Reuses a physically empty rack-level row for the next BOX. */
    public void replaceEmptyBox(Product product, LocalDateTime receivedAt) {
        if (safeQuantity() != 0 || product == null || receivedAt == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        this.product = product;
        this.receivedAt = receivedAt;
        this.quantity = 0;
        this.inboundQuantity = 0;
        this.outboundQuantity = 0;
    }

    public void increaseQuantity(int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        int nextQuantity = safeQuantity() + amount;
        requireValidBoxQuantity(product, nextQuantity);
        this.quantity = nextQuantity;
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

    /**
     * 기존 Task 및 API 계약은 숫자 itemId를 사용하므로 호환 접근자를 유지한다.
     * 실제 저장 관계는 product_id 외래 키와 Product 연관관계로 관리한다.
     */
    public Long getItemId() {
        return product == null ? null : product.getId();
    }

    private int safeQuantity() {
        return quantity == null ? 0 : quantity;
    }

    private int safeCount(Integer value) {
        return value == null ? 0 : value;
    }

    private static void requireValidBoxQuantity(Product product, Integer quantity) {
        if (product == null || product.getUnitsPerBox() == null || quantity == null
                || quantity < 0 || quantity > product.getUnitsPerBox()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private static void requireValidRackLevel(Integer rackLevel) {
        if (rackLevel == null || rackLevel < 1 || rackLevel > 3) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
