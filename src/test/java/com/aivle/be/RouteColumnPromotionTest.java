package com.aivle.be;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteColumnPromotionTest {

    @Test
    void legacyNodeRouteKeysArePromotedToTypedFields() {
        Warehouse warehouse = Warehouse.create("warehouse", 10, 10, null);

        WarehouseNode node = WarehouseNode.create(
                warehouse,
                "STORAGE_ZONE",
                1.0,
                2.0,
                "K0_1_ACCESS_A",
                NodeType.RACK_ACCESS,
                WarehouseNode.RouteProperties.empty(),
                Map.of(
                        "service_only", true,
                        "transit_allowed", false,
                        "holding_allowed", false,
                        "node_capacity", 1,
                        "rack_id", "K0_1",
                        "side", "A",
                        "label", "rack access"
                )
        );

        assertTrue(node.getServiceOnly());
        assertFalse(node.getTransitAllowed());
        assertFalse(node.getHoldingAllowed());
        assertEquals(1, node.getNodeCapacity());
        assertEquals("RACK", node.getResourceType());
        assertEquals("K0_1", node.getResourceCode());
        assertEquals("A", node.getSide());
        assertEquals(Map.of("label", "rack access"), node.getRouteAttributes());
    }

    @Test
    void legacyEdgeRouteKeysArePromotedToTypedFields() {
        Warehouse warehouse = Warehouse.create("warehouse", 10, 10, null);
        WarehouseNode source = routeNode(warehouse, "R0_0", 0.0);
        WarehouseNode target = routeNode(warehouse, "R0_1", 1.0);

        WarehouseEdge edge = WarehouseEdge.create(
                source,
                target,
                1.0,
                WarehouseEdge.DirectionType.A_TO_B,
                "H0_0",
                WarehouseEdge.RouteProperties.empty(),
                Map.of(
                        "type", "lane",
                        "speed_limit_mps", 0.5,
                        "nominal_travel_time_ms", 2000,
                        "cost", 1.5,
                        "physical_resource_code", "LANE-01",
                        "service_only", false,
                        "mobile_robot_traversable", true,
                        "lane_group", "A"
                )
        );

        assertEquals("lane", edge.getEdgeType());
        assertEquals(0.5, edge.getSpeedLimitMps());
        assertEquals(2000L, edge.getNominalTravelTimeMs());
        assertEquals(1.5, edge.getCost());
        assertEquals("LANE-01", edge.getPhysicalResourceCode());
        assertFalse(edge.getServiceOnly());
        assertTrue(edge.getMobileRobotTraversable());
        assertEquals(Map.of("lane_group", "A"), edge.getRouteAttributes());
    }

    @Test
    void sixArgumentNodeFactoryNoLongerExists() {
        assertThrows(
                NoSuchMethodException.class,
                () -> WarehouseNode.class.getDeclaredMethod(
                        "create",
                        Warehouse.class,
                        String.class,
                        Double.class,
                        Double.class,
                        String.class,
                        NodeType.class
                )
        );
    }

    @Test
    void retiredNodeCanBeReactivatedWithoutChangingItsIdentity() {
        Warehouse warehouse = Warehouse.create("warehouse", 10, 10, null);
        WarehouseNode node = routeNode(warehouse, "R0_0", 0.0);

        assertTrue(node.isActive());

        node.retire();
        assertFalse(node.isActive());

        node.activate();
        assertTrue(node.isActive());
    }

    private WarehouseNode routeNode(Warehouse warehouse, String code, double x) {
        return WarehouseNode.create(
                warehouse,
                "MOVING_ZONE",
                x,
                0.0,
                code,
                NodeType.ROUTE,
                WarehouseNode.RouteProperties.empty(),
                Map.of()
        );
    }
}
