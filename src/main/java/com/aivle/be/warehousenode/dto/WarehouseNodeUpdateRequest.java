package com.aivle.be.warehousenode.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WarehouseNodeUpdateRequest {

    private String zoneId;
    private Double x;
    private Double y;
}