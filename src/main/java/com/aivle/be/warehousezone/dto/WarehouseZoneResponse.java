package com.aivle.be.warehousezone.dto;

import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.entity.WarehouseZone.ZoneType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class WarehouseZoneResponse {

    private Long id;
    private Long warehouseId;
    private String name;
    private ZoneType zoneType;
    private String description;
    private Double minX;
    private Double maxX;
    private Double minY;
    private Double maxY;

    public static WarehouseZoneResponse from(WarehouseZone zone) {
        return new WarehouseZoneResponse(
                zone.getId(),
                zone.getWarehouse().getId(),
                zone.getName(),
                zone.getZoneType(),
                zone.getDescription(),
                zone.getMinX(),
                zone.getMaxX(),
                zone.getMinY(),
                zone.getMaxY()
        );
    }
}