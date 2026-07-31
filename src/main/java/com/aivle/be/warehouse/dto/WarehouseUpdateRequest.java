package com.aivle.be.warehouse.dto;

import com.aivle.be.warehouse.entity.Warehouse;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 창고 수정 요청.
 *
 * 보내지 않은 항목은 기존 값을 유지한다.
 */
@Getter
@NoArgsConstructor
public class WarehouseUpdateRequest {

    private String name;
    private Integer width;
    private Integer height;
    private String location;
    private String description;
    private Warehouse.WarehouseStatus status;
}
