package com.aivle.be.warehouseedge.entity;

import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "warehouse_edge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseEdge {

    private static final Set<String> CORE_ROUTE_ATTRIBUTE_KEYS = Set.of(
            "type",
            "direction",
            "speed_limit_mps",
            "nominal_travel_time_ms",
            "cost",
            "base_cost",
            "resource_id",
            "physical_resource_code",
            "service_only",
            "mobile_robot_traversable"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "edge_id")
    private Long id;

    /**
     * 창고 그래프상의 간선 코드. (예: H0_0, V5_1, RA_K0_1_A)
     *
     * 프론트 warehouse_graph.json 및 AI(cuOpt/MAPF) 응답의 edge_id 와 대응한다.
     * 외부와 주고받을 때는 숫자 PK 대신 이 코드를 쓴다.
     */
    @Column(name = "edge_code", length = 50)
    private String edgeCode;

    private Double distance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_node_id", nullable = false)
    private WarehouseNode fromNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_node_id", nullable = false)
    private WarehouseNode toNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_type", nullable = false)
    private DirectionType directionType;

    @Column(name = "edge_type", length = 40)
    private String edgeType;

    @Column(name = "speed_limit_mps")
    private Double speedLimitMps;

    @Column(name = "nominal_travel_time_ms")
    private Long nominalTravelTimeMs;

    @Column(name = "cost")
    private Double cost;

    @Column(name = "physical_resource_code", length = 100)
    private String physicalResourceCode;

    @Column(name = "service_only")
    private Boolean serviceOnly;

    @Column(name = "mobile_robot_traversable")
    private Boolean mobileRobotTraversable;

    /** Optional edge metadata that is not part of the typed routing contract. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_attributes", columnDefinition = "jsonb")
    private Map<String, Object> routeAttributes = new LinkedHashMap<>();

    public static WarehouseEdge create(
            WarehouseNode fromNode,
            WarehouseNode toNode,
            Double distance,
            DirectionType directionType,
            String edgeCode,
            RouteProperties routeProperties,
            Map<String, Object> routeAttributes
    ) {
        RouteProperties properties = resolveForCreate(
                distance,
                edgeCode,
                routeProperties,
                routeAttributes
        );
        WarehouseEdge edge = new WarehouseEdge();
        edge.fromNode = fromNode;
        edge.toNode = toNode;
        edge.distance = distance;
        edge.directionType = directionType;
        edge.edgeCode = edgeCode;
        edge.edgeType = properties.edgeType();
        edge.speedLimitMps = properties.speedLimitMps();
        edge.nominalTravelTimeMs = properties.nominalTravelTimeMs();
        edge.cost = properties.cost();
        edge.physicalResourceCode = properties.physicalResourceCode();
        edge.serviceOnly = properties.serviceOnly();
        edge.mobileRobotTraversable = properties.mobileRobotTraversable();
        edge.routeAttributes = copyMetadata(routeAttributes);
        return edge;
    }

    public void update(
            WarehouseNode fromNode,
            WarehouseNode toNode,
            Double distance,
            DirectionType directionType,
            String edgeCode,
            RouteProperties routeProperties,
            Map<String, Object> routeAttributes
    ) {
        this.fromNode = fromNode;
        this.toNode = toNode;
        this.distance = distance;
        this.directionType = directionType;
        if (edgeCode != null) {
            this.edgeCode = edgeCode;
        }
        RouteProperties properties = resolveForUpdate(routeProperties, routeAttributes);
        if (properties.edgeType() != null) {
            this.edgeType = properties.edgeType();
        }
        if (properties.speedLimitMps() != null) {
            this.speedLimitMps = properties.speedLimitMps();
        }
        if (properties.nominalTravelTimeMs() != null) {
            this.nominalTravelTimeMs = properties.nominalTravelTimeMs();
        }
        if (properties.cost() != null) {
            this.cost = properties.cost();
        }
        if (properties.physicalResourceCode() != null) {
            this.physicalResourceCode = properties.physicalResourceCode();
        }
        if (properties.serviceOnly() != null) {
            this.serviceOnly = properties.serviceOnly();
        }
        if (properties.mobileRobotTraversable() != null) {
            this.mobileRobotTraversable = properties.mobileRobotTraversable();
        }
        if (routeAttributes != null) {
            this.routeAttributes = copyMetadata(routeAttributes);
        }
    }

    /**
     * 간선 코드를 지정한다. (그래프 동기화·마이그레이션용)
     */
    public void assignEdgeCode(String edgeCode) {
        this.edgeCode = edgeCode;
    }

    private static RouteProperties resolveForCreate(
            Double distance,
            String edgeCode,
            RouteProperties supplied,
            Map<String, Object> attributes
    ) {
        RouteProperties resolved = resolveForUpdate(supplied, attributes);
        boolean serviceOnly = resolved.serviceOnly() != null && resolved.serviceOnly();
        double speed = resolved.speedLimitMps() == null || resolved.speedLimitMps() <= 0
                ? 1.0
                : resolved.speedLimitMps();
        double normalizedDistance = distance == null ? 0.0 : distance;
        long travelTime = resolved.nominalTravelTimeMs() == null
                ? Math.round(normalizedDistance / speed * 1000)
                : Math.max(0, resolved.nominalTravelTimeMs());
        double cost = resolved.cost() == null ? normalizedDistance : resolved.cost();
        return new RouteProperties(
                resolved.edgeType() == null
                        ? (serviceOnly ? "service_spur" : "lane")
                        : resolved.edgeType(),
                speed,
                travelTime,
                cost,
                resolved.physicalResourceCode() == null
                        ? edgeCode
                        : resolved.physicalResourceCode(),
                serviceOnly,
                resolved.mobileRobotTraversable() == null
                        || resolved.mobileRobotTraversable()
        );
    }

    private static RouteProperties resolveForUpdate(
            RouteProperties supplied,
            Map<String, Object> attributes
    ) {
        RouteProperties values = supplied == null ? RouteProperties.empty() : supplied;
        return new RouteProperties(
                firstNonBlank(values.edgeType(), stringAttribute(attributes, "type")),
                firstNonNull(values.speedLimitMps(), doubleAttribute(attributes, "speed_limit_mps")),
                firstNonNull(values.nominalTravelTimeMs(), longAttribute(attributes, "nominal_travel_time_ms")),
                firstNonNull(values.cost(), doubleAttribute(attributes, "cost", "base_cost")),
                firstNonBlank(
                        values.physicalResourceCode(),
                        stringAttribute(attributes, "physical_resource_code", "resource_id")
                ),
                firstNonNull(values.serviceOnly(), booleanAttribute(attributes, "service_only")),
                firstNonNull(
                        values.mobileRobotTraversable(),
                        booleanAttribute(attributes, "mobile_robot_traversable")
                )
        );
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> values) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (!CORE_ROUTE_ATTRIBUTE_KEYS.contains(key) && value != null) {
                    metadata.put(key, value);
                }
            });
        }
        return metadata;
    }

    private static Boolean booleanAttribute(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? null : Boolean.valueOf(value.toString());
    }

    private static Double doubleAttribute(Map<String, Object> values, String... keys) {
        Object value = attribute(values, keys);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long longAttribute(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringAttribute(Map<String, Object> values, String... keys) {
        Object value = attribute(values, keys);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Object attribute(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public enum DirectionType {
        BOTH,
        A_TO_B,
        B_TO_A
    }

    public record RouteProperties(
            String edgeType,
            Double speedLimitMps,
            Long nominalTravelTimeMs,
            Double cost,
            String physicalResourceCode,
            Boolean serviceOnly,
            Boolean mobileRobotTraversable
    ) {
        public static RouteProperties empty() {
            return new RouteProperties(null, null, null, null, null, null, null);
        }
    }
}
