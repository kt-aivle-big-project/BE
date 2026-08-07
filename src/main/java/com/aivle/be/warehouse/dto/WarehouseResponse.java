package com.aivle.be.warehouse.dto;

import com.aivle.be.warehouse.entity.Warehouse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class WarehouseResponse {

    private Long id;
    private String name;
    private Integer width;
    private Integer height;
    private String location;
    private String description;
    private Warehouse.WarehouseStatus status;
    private Long userId;
    private Long sourceTemplateId;

    /** 공용 창고면 화면에서 수정·삭제 버튼을 감춘다. */
    private boolean shared;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WarehouseResponse from(Warehouse warehouse) {
        return WarehouseResponse.builder()
                .id(warehouse.getId())
                .name(warehouse.getName())
                .width(warehouse.getWidth())
                .height(warehouse.getHeight())
                .location(warehouse.getLocation())
                .description(warehouse.getDescription())
                .status(warehouse.getStatus())
                .userId(warehouse.getUser().getId())
                .sourceTemplateId(warehouse.getSourceTemplate() == null
                        ? null
                        : warehouse.getSourceTemplate().getId())
                .shared(warehouse.isShared())
                .createdAt(warehouse.getCreatedAt())
                .updatedAt(warehouse.getUpdatedAt())
                .build();
    }
}
