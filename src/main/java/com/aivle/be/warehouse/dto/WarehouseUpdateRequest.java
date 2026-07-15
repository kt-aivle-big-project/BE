package com.aivle.be.warehouse.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WarehouseUpdateRequest {

    private String name;
    private Integer width;
    private Integer height;
}