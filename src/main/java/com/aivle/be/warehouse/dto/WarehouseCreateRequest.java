package com.aivle.be.warehouse.dto;

import com.aivle.be.warehouse.entity.Warehouse;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WarehouseCreateRequest {

    private String name;
    private Integer width;
    private Integer height;
    private Long userId;
    private String location;
    private String description;
    private Warehouse.WarehouseStatus status;
}
