package com.aivle.be.warehouse.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor

public class WarehouseCreateRequest {

    private String name;
    private Integer width;
    private Integer height;
    private Long userId;
}
