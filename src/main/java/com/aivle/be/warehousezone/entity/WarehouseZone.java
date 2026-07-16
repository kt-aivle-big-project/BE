package com.aivle.be.warehousezone.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "warehouse_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "zone_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_type", nullable = false, length = 30)
    private ZoneType zoneType;

    @Column(length = 255)
    private String description;

    @Column(name = "min_x", nullable = false)
    private Double minX;

    @Column(name = "max_x", nullable = false)
    private Double maxX;

    @Column(name = "min_y", nullable = false)
    private Double minY;

    @Column(name = "max_y", nullable = false)
    private Double maxY;

    public static WarehouseZone create(
            Warehouse warehouse,
            String name,
            ZoneType zoneType,
            String description,
            Double minX,
            Double maxX,
            Double minY,
            Double maxY
    ) {
        WarehouseZone zone = new WarehouseZone();
        zone.warehouse = warehouse;
        zone.name = name;
        zone.zoneType = zoneType;
        zone.description = description;
        zone.minX = minX;
        zone.maxX = maxX;
        zone.minY = minY;
        zone.maxY = maxY;
        return zone;
    }

    public void update(
            String name,
            ZoneType zoneType,
            String description,
            Double minX,
            Double maxX,
            Double minY,
            Double maxY
    ) {
        this.name = name;
        this.zoneType = zoneType;
        this.description = description;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    public enum ZoneType {
        STORAGE,
        MOVING,
        INBOUND,
        OUTBOUND,
        CHARGING,
        RESTRICTED
    }
}