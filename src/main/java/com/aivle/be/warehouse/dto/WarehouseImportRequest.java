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

public record WarehouseImportRequest(

        @NotBlank(message = "창고 이름은 필수입니다.")
        String name,

        @NotNull @Positive Integer width,
        @NotNull @Positive Integer height,

        Long userId,

        String location,
        String description,
        com.aivle.be.warehouse.entity.Warehouse.WarehouseStatus status,

        Integer robotCount,

        @NotNull(message = "지도 정보가 필요합니다.")
        @Valid
        MapPayload map
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MapPayload(
            @NotEmpty(message = "노드가 비어 있습니다.")
            @Valid List<MapNode> nodes,

            @NotEmpty(message = "간선이 비어 있습니다.")
            @Valid List<MapEdge> edges
    ) {}

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
