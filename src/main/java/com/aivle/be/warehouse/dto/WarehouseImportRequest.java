package com.aivle.be.warehouse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

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
            String rack_id,
            String side,
            String handoff_id,
            String station_id,
            String buffer_id,
            String resource_id,
            String adjacent_route_node,
            String label,
            Integer row,
            Integer col,
            Integer index,
            Boolean service_only,
            Boolean transit_allowed,
            Boolean holding_allowed,
            Integer node_capacity,
            Boolean active_for_new_work,
            Boolean legacy_direct_route,
            Map<String, Object> route_attributes
    ) {
        public Map<String, Object> routeAttributes() {
            Map<String, Object> values = new LinkedHashMap<>();
            if (route_attributes != null) {
                values.putAll(route_attributes);
            }
            put(values, "adjacent_route_node", adjacent_route_node);
            put(values, "label", label);
            put(values, "row", row);
            put(values, "col", col);
            put(values, "index", index);
            put(values, "active_for_new_work", active_for_new_work);
            put(values, "legacy_direct_route", legacy_direct_route);
            return values;
        }

        public String resourceType() {
            String normalizedType = type == null ? "" : type.trim().toLowerCase();
            return switch (normalizedType) {
                case "rack_storage", "rack_access" -> "RACK";
                case "inbound_handoff_access" -> "INBOUND_HANDOFF";
                case "outbound_station_access" -> "OUTBOUND_STATION";
                case "empty_tote_buffer_access" -> "EMPTY_TOTE_BUFFER";
                default -> resource_id == null ? null : "RESOURCE";
            };
        }

        /**
         * 이 노드가 가리키는 설비 코드.
         *
         * <p>지도 JSON 이 설비 코드를 따로 적어 두지 않은 경우가 많다.
         * 예를 들어 빈 토트 버퍼는 {@code {"id": "ETB_0", "type":
         * "empty_tote_buffer_access", ...}} 처럼 {@code buffer_id} 없이 온다.
         * 이때 코드가 비면 Neo4j 계약에 {@code buffer_id} 가 빠져
         * AI 가 경로 계획을 세우지 못한다.
         *
         * <p>설비 접근 노드는 하나가 설비 하나에 대응하므로,
         * 적힌 코드가 없으면 노드 이름을 그대로 설비 코드로 쓴다.
         */
        public String resourceCode() {
            if (rack_id != null) {
                return rack_id;
            }
            if (handoff_id != null) {
                return handoff_id;
            }
            if (station_id != null) {
                return station_id;
            }
            if (buffer_id != null) {
                return buffer_id;
            }
            if (resource_id != null) {
                return resource_id;
            }
            return resourceType() == null ? null : id;
        }
    }

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
            Double distance_m,
            String type,
            String direction,
            Double speed_limit_mps,
            Long nominal_travel_time_ms,
            Double cost,
            String resource_id,
            String physical_resource_code,
            Boolean service_only,
            Boolean mobile_robot_traversable,
            Map<String, Object> route_attributes
    ) {
        public Map<String, Object> routeAttributes() {
            return route_attributes == null
                    ? Map.of()
                    : new LinkedHashMap<>(route_attributes);
        }

        public String physicalResourceCode() {
            return physical_resource_code == null ? resource_id : physical_resource_code;
        }
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
