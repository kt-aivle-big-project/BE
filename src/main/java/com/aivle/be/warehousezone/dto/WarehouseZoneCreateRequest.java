package com.aivle.be.warehousezone.dto;

import com.aivle.be.warehousezone.entity.WarehouseZone.ZoneType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class WarehouseZoneCreateRequest {

    private Long warehouseId;
    private String name;
    private ZoneType zoneType;
    private String description;
    private Double minX;
    private Double maxX;
    private Double minY;
    private Double maxY;
}