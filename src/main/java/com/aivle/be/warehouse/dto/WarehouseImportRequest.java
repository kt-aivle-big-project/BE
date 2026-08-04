package com.aivle.be.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * 지도 JSON 으로 창고를 만드는 요청.
 *
 * <p>화면에서 올린 warehouse_graph.json 을 그대로 담는다.
 * 백엔드가 노드·간선을 읽어 랙·충전소·보관위치·로봇까지 만들어준다.
 */
public record WarehouseImportRequest(

        @NotBlank(message = "창고 이름은 필수입니다.")
        String name,

        @NotNull @Positive Integer width,
        @NotNull @Positive Integer height,

        /** 창고 소유자. 없으면 로그인한 사용자로 채운다. */
        Long userId,

        String location,
        String description,
        com.aivle.be.warehouse.entity.Warehouse.WarehouseStatus status,

        /** 배치할 로봇 대수. 충전 슬롯 수를 넘지 않는다. */
        Integer robotCount,

        @NotNull(message = "지도 정보가 필요합니다.")
        @Valid
        MapPayload map
) {

    /**
     * 지도 JSON 의 본문.
     * 우리가 쓰지 않는 필드(summary, routing_model 등)는 무시한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapPayload(
            @NotEmpty(message = "노드가 비어 있습니다.")
            @Valid List<MapNode> nodes,

            @NotEmpty(message = "간선이 비어 있습니다.")
            @Valid List<MapEdge> edges
    ) {}

    /**
     * 지도 노드 하나.
     *
     * <pre>
     * { "id": "R0_0", "type": "route", "x": 4.1, "y": 0.72 }
     * { "id": "K0_1_ACCESS_A", "type": "rack_access", "rack_id": "K0_1", ... }
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapNode(
            @NotBlank String id,
            String type,
            Double x,
            Double y,

            /** 랙 접근 노드가 가리키는 랙 코드 */
            String rack_id
    ) {}

    /**
     * 지도 간선 하나.
     *
     * <pre>
     * { "id": "H0_0", "source": "R0_0", "target": "R0_1", "distance_m": 2.25 }
     * </pre>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapEdge(
            String id,
            @NotBlank String source,
            @NotBlank String target,
            Double distance_m
    ) {}
}
