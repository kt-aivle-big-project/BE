package com.aivle.be.warehousenode.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.domain.NodeType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "warehouse_node")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WarehouseNode {

    private static final Set<String> CORE_ROUTE_ATTRIBUTE_KEYS = Set.of(
            "type",
            "service_only",
            "transit_allowed",
            "holding_allowed",
            "node_capacity",
            "resource_type",
            "resource_code",
            "resource_id",
            "rack_id",
            "handoff_id",
            "station_id",
            "buffer_id",
            "side"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "node_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "zone_id")
    private String zoneId;

    @Column(name = "node_code", length = 50)
    private String nodeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", length = 30)
    private NodeType nodeType;

    private Double x;

    private Double y;

    @Column(name = "service_only")
    private Boolean serviceOnly;

    @Column(name = "transit_allowed")
    private Boolean transitAllowed;

    @Column(name = "holding_allowed")
    private Boolean holdingAllowed;

    @Column(name = "node_capacity")
    private Integer nodeCapacity;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "side", length = 30)
    private String side;

    @Column(name = "is_active", nullable = false, columnDefinition = "boolean default true")
    private Boolean active = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_attributes", columnDefinition = "jsonb")
    private Map<String, Object> routeAttributes = new LinkedHashMap<>();

    public static WarehouseNode create(
            Warehouse warehouse,
            String zoneId,
            Double x,
            Double y,
            String nodeCode,
            NodeType nodeType,
            RouteProperties routeProperties,
            Map<String, Object> routeAttributes
    ) {
        RouteProperties properties = resolveForCreate(nodeType, routeProperties, routeAttributes);
        WarehouseNode node = new WarehouseNode();
        node.warehouse = warehouse;
        node.zoneId = zoneId;
        node.x = x;
        node.y = y;
        node.nodeCode = nodeCode;
        node.nodeType = nodeType;
        node.serviceOnly = properties.serviceOnly();
        node.transitAllowed = properties.transitAllowed();
        node.holdingAllowed = properties.holdingAllowed();
        node.nodeCapacity = properties.nodeCapacity();
        node.resourceType = properties.resourceType();
        node.resourceCode = properties.resourceCode();
        node.side = properties.side();
        node.active = true;
        node.routeAttributes = copyMetadata(routeAttributes);
        return node;
    }

    public void update(
            String zoneId,
            Double x,
            Double y,
            String nodeCode,
            NodeType nodeType,
            RouteProperties routeProperties,
            Map<String, Object> routeAttributes
    ) {
        this.zoneId = zoneId;
        this.x = x;
        this.y = y;
        if (nodeCode != null) {
            this.nodeCode = nodeCode;
        }
        if (nodeType != null) {
            this.nodeType = nodeType;
        }

        RouteProperties properties = resolveForUpdate(
                nodeType == null ? this.nodeType : nodeType,
                routeProperties,
                routeAttributes
        );
        if (properties.serviceOnly() != null) {
            this.serviceOnly = properties.serviceOnly();
        }
        if (properties.transitAllowed() != null) {
            this.transitAllowed = properties.transitAllowed();
        }
        if (properties.holdingAllowed() != null) {
            this.holdingAllowed = properties.holdingAllowed();
        }
        if (properties.nodeCapacity() != null) {
            this.nodeCapacity = properties.nodeCapacity();
        }
        if (properties.resourceType() != null) {
            this.resourceType = properties.resourceType();
        }
        if (properties.resourceCode() != null) {
            this.resourceCode = properties.resourceCode();
        }
        if (properties.side() != null) {
            this.side = properties.side();
        }
        if (routeAttributes != null) {
            this.routeAttributes = copyMetadata(routeAttributes);
        }
    }

    private static RouteProperties resolveForCreate(
            NodeType nodeType,
            RouteProperties supplied,
            Map<String, Object> attributes
    ) {
        RouteProperties resolved = resolveForUpdate(nodeType, supplied, attributes);
        boolean serviceOnly = resolved.serviceOnly() != null
                ? resolved.serviceOnly()
                : nodeType != null && nodeType.isServiceAccess();
        boolean transitAllowed = resolved.transitAllowed() != null
                ? resolved.transitAllowed()
                : !serviceOnly;
        boolean holdingAllowed = resolved.holdingAllowed() == null || resolved.holdingAllowed();
        int nodeCapacity = resolved.nodeCapacity() == null || resolved.nodeCapacity() <= 0
                ? 1
                : resolved.nodeCapacity();
        return new RouteProperties(
                serviceOnly,
                transitAllowed,
                holdingAllowed,
                nodeCapacity,
                resolved.resourceType(),
                resolved.resourceCode(),
                resolved.side()
        );
    }

    private static RouteProperties resolveForUpdate(
            NodeType nodeType,
            RouteProperties supplied,
            Map<String, Object> attributes
    ) {
        RouteProperties values = supplied == null ? RouteProperties.empty() : supplied;
        String resourceCode = firstNonBlank(
                values.resourceCode(),
                stringAttribute(attributes, "resource_code", "rack_id", "handoff_id",
                        "station_id", "buffer_id", "resource_id")
        );
        String resourceType = firstNonBlank(
                values.resourceType(),
                stringAttribute(attributes, "resource_type")
        );
        if (resourceType == null && resourceCode != null) {
            resourceType = resourceTypeFor(nodeType);
        }
        return new RouteProperties(
                firstNonNull(values.serviceOnly(), booleanAttribute(attributes, "service_only")),
                firstNonNull(values.transitAllowed(), booleanAttribute(attributes, "transit_allowed")),
                firstNonNull(values.holdingAllowed(), booleanAttribute(attributes, "holding_allowed")),
                firstNonNull(values.nodeCapacity(), integerAttribute(attributes, "node_capacity")),
                resourceType,
                resourceCode,
                firstNonBlank(values.side(), stringAttribute(attributes, "side"))
        );
    }

    private static String resourceTypeFor(NodeType nodeType) {
        if (nodeType == null) {
            return null;
        }
        return switch (nodeType) {
            case RACK_STORAGE, RACK_ACCESS -> "RACK";
            case INBOUND_HANDOFF_ACCESS -> "INBOUND_HANDOFF";
            case OUTBOUND_STATION_ACCESS -> "OUTBOUND_STATION";
            case EMPTY_TOTE_BUFFER_ACCESS -> "EMPTY_TOTE_BUFFER";
            default -> null;
        };
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

    private static Integer integerAttribute(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringAttribute(Map<String, Object> values, String... keys) {
        if (values == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
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

    public boolean isStorageNode() {
        return nodeType != null && nodeType.isStorage();
    }

    public boolean isActive() {
        return !Boolean.FALSE.equals(active);
    }

    public void activate() {
        this.active = true;
    }

    public void retire() {
        this.active = false;
    }

    public record RouteProperties(
            Boolean serviceOnly,
            Boolean transitAllowed,
            Boolean holdingAllowed,
            Integer nodeCapacity,
            String resourceType,
            String resourceCode,
            String side
    ) {
        public static RouteProperties empty() {
            return new RouteProperties(null, null, null, null, null, null, null);
        }
    }
}
