package com.aivle.be;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.product.entity.Product;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeLevelRackStorageTest {

    @Test
    void warehouseItemStoresOneBoxAtAnExplicitRackLevel() {
        Warehouse warehouse = Warehouse.create("warehouse", 10, 10, null);
        WarehouseNode node = WarehouseNode.create(
                warehouse,
                "STORAGE_ZONE",
                1.0,
                1.0,
                "K1_1",
                NodeType.RACK_STORAGE,
                WarehouseNode.RouteProperties.empty(),
                Map.of()
        );
        StorageLocation location = new StorageLocation();
        location.setWarehouse(warehouse);
        location.setNode(node);
        Product product = Product.create(
                "ITEM-001", "item", "test", "EA", 20,
                null, "AMBIENT", false
        );

        WarehouseItem item = WarehouseItem.create(
                warehouse, location, 3, node, product,
                LocalDateTime.now(), 20
        );

        assertEquals(3, item.getRackLevel());
        assertEquals(20, item.getQuantity());
    }

    @Test
    void warehouseItemRejectsLevelsOutsideOneToThree() {
        Warehouse warehouse = Warehouse.create("warehouse", 10, 10, null);
        WarehouseNode node = WarehouseNode.create(
                warehouse,
                "STORAGE_ZONE",
                1.0,
                1.0,
                "K1_1",
                NodeType.RACK_STORAGE,
                WarehouseNode.RouteProperties.empty(),
                Map.of()
        );
        StorageLocation location = new StorageLocation();
        location.setWarehouse(warehouse);
        location.setNode(node);
        Product product = Product.create(
                "ITEM-001", "item", "test", "EA", 20,
                null, "AMBIENT", false
        );

        assertThrows(
                BusinessException.class,
                () -> WarehouseItem.create(
                        warehouse, location, 4, node, product,
                        LocalDateTime.now(), 20
                )
        );
    }

    @Test
    void migrationUsesCompositeRackAndLevelUniqueness() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/seed/V08_three_level_rack_storage.sql"
        ));

        assertTrue(sql.contains("CHECK (rack_level BETWEEN 1 AND 3)"));
        assertTrue(sql.contains("UNIQUE (storage_location_id, rack_level)"));
        assertTrue(sql.contains("SET rack_level = 1"));
    }
}
