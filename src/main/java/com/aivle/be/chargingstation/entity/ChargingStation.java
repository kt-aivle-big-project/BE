package com.aivle.be.chargingstation.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "charging_station")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChargingStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "charging_station_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false, unique = true)
    private WarehouseNode node;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ChargingStationStatus status;

    @Column(name = "charging_power", nullable = false)
    private Double chargingPower;

    public static ChargingStation create(
            Warehouse warehouse,
            WarehouseNode node,
            String name,
            ChargingStationStatus status,
            Double chargingPower
    ) {
        ChargingStation chargingStation = new ChargingStation();
        chargingStation.warehouse = warehouse;
        chargingStation.node = node;
        chargingStation.name = name;
        chargingStation.status = status;
        chargingStation.chargingPower = chargingPower;
        return chargingStation;
    }

    public void update(
            String name,
            ChargingStationStatus status,
            Double chargingPower
    ) {
        this.name = name;
        this.status = status;
        this.chargingPower = chargingPower;
    }

    public enum ChargingStationStatus {
        AVAILABLE,
        OCCUPIED,
        UNAVAILABLE,
        MAINTENANCE
    }
}