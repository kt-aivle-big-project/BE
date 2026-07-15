package com.aivle.be.warehouse.dto;

import com.aivle.be.warehouse.entity.Warehouse;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WarehouseResponse {

    private Long id;
    private String name;
    private Integer width;
    private Integer height;
    private Long userId;

    public static WarehouseResponse from(Warehouse warehouse) {
        return WarehouseResponse.builder()
                .id(warehouse.getId())
                .name(warehouse.getName())
                .width(warehouse.getWidth())
                .height(warehouse.getHeight())
                .userId(warehouse.getUser().getId())
                .build();
    }
}