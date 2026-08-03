package com.aivle.be.optimization.service;

import com.aivle.be.graph.service.AiRouteGraphSyncService;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * Writes BE-owned master/runtime facts into the native PostgreSQL contract
 * read by LARO. IDs are deterministic so repeated synchronization is safe.
 */
@Service
@RequiredArgsConstructor
public class AiPostgresContractSyncService {

    public record ContractEvents(
            List<String> orderIds,
            List<String> inboundIds
    ) {
    }

    private final JdbcTemplate jdbc;
    private final TaskRepository taskRepository;

    @Transactional
    public void syncImportedWarehouse(
            Long warehouseId,
            String warehouseName,
            WarehouseImportRequest.MapPayload map
    ) {
        String wid = AiRouteGraphSyncService.toAiWarehouseId(warehouseId);
        jdbc.update("""
                INSERT INTO warehouses (warehouse_id, label)
                VALUES (?, ?)
                ON CONFLICT (warehouse_id) DO UPDATE
                SET label = EXCLUDED.label, active = true, updated_at = now()
                """, wid, warehouseName);

        Map<String, List<String>> rackAccess = map.nodes().stream()
                .filter(node -> "rack_access".equals(node.type()))
                .filter(node -> node.rack_id() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        WarehouseImportRequest.MapNode::rack_id,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                WarehouseImportRequest.MapNode::id,
                                java.util.stream.Collectors.toList()
                        )
                ));
        rackAccess.forEach((rackId, accessIds) -> {
            jdbc.update("""
                    INSERT INTO racks (warehouse_id, rack_id, access_node_ids)
                    VALUES (?, ?, to_jsonb(?::text[]))
                    ON CONFLICT (warehouse_id, rack_id) DO UPDATE
                    SET access_node_ids = EXCLUDED.access_node_ids
                    """, wid, rackId, accessIds.toArray(String[]::new));
            jdbc.update("""
                    INSERT INTO rack_slots (warehouse_id, rack_id, level, status, capacity)
                    VALUES (?, ?, 1, 'EMPTY', 100)
                    ON CONFLICT (warehouse_id, rack_id, level) DO NOTHING
                    """, wid, rackId);
        });

        List<String> chutes = map.nodes().stream()
                .filter(node -> "outbound".equals(node.type()))
                .map(WarehouseImportRequest.MapNode::id)
                .toList();
        chutes.forEach(chute -> jdbc.update("""
                INSERT INTO outbound_chutes (warehouse_id, chute_id, label)
                VALUES (?, ?, ?)
                ON CONFLICT (warehouse_id, chute_id) DO UPDATE SET label=EXCLUDED.label
                """, wid, chute, chute));

        map.nodes().stream()
                .filter(node -> "outbound_station_access".equals(node.type()))
                .filter(node -> node.station_id() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        WarehouseImportRequest.MapNode::station_id,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                WarehouseImportRequest.MapNode::id,
                                java.util.stream.Collectors.toList()
                        )
                ))
                .forEach((stationId, accessIds) ->
                        syncStation(wid, stationId, accessIds, chutes));
        map.nodes().stream()
                .filter(node -> "inbound_handoff_access".equals(node.type()))
                .filter(node -> node.handoff_id() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        WarehouseImportRequest.MapNode::handoff_id,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.mapping(
                                WarehouseImportRequest.MapNode::id,
                                java.util.stream.Collectors.toList()
                        )
                ))
                .forEach((handoffId, accessIds) ->
                        syncHandoff(wid, handoffId, accessIds));
        map.nodes().stream()
                .filter(node -> "empty_tote_buffer_access".equals(node.type()))
                .filter(node -> node.buffer_id() != null)
                .forEach(node -> jdbc.update("""
                        INSERT INTO empty_tote_buffers
                          (warehouse_id, buffer_id, access_node_ids, capacity, status)
                        VALUES (?, ?, to_jsonb(ARRAY[?]::text[]), 20, 'available')
                        ON CONFLICT (warehouse_id, buffer_id) DO UPDATE
                        SET access_node_ids=EXCLUDED.access_node_ids,
                            status=EXCLUDED.status
                        """, wid, node.buffer_id(), node.id()));

        syncInventory(warehouseId, wid);
    }

    @Transactional
    public ContractEvents syncSimulationTasks(Long simulationRunId, Long warehouseId) {
        String wid = AiRouteGraphSyncService.toAiWarehouseId(warehouseId);
        normalizeFacilityStatuses(wid);
        releaseInactiveInboundPlacements(wid);
        syncInventory(warehouseId, wid);
        List<Task> tasks =
                taskRepository.findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId);
        List<String> orderIds = new ArrayList<>();
        List<String> inboundIds = new ArrayList<>();
        for (Task task : tasks) {
            if (task.getTaskType() == TaskType.OUTBOUND) {
                String orderId = syncOutboundTask(wid, simulationRunId, task);
                if (orderId != null) {
                    orderIds.add(orderId);
                }
            } else if (task.getTaskType() == TaskType.INBOUND) {
                String inboundId = syncInboundTask(wid, simulationRunId, task);
                if (inboundId != null) {
                    inboundIds.add(inboundId);
                }
            }
        }
        return new ContractEvents(List.copyOf(orderIds), List.copyOf(inboundIds));
    }

    private void releaseInactiveInboundPlacements(String wid) {
        jdbc.update("""
                UPDATE inbound_receipts ir
                SET target_rack_id=NULL, target_rack_level=NULL,
                    status='pending', updated_at=now()
                FROM task t JOIN simulation_runs sr
                  ON sr.simulation_run_id=t.simulation_run_id
                WHERE t.inbound_id=ir.inbound_id
                  AND ir.warehouse_id=?
                  AND ir.status IN ('planned','in_transit')
                  AND sr.status IN ('CREATED','STOPPED','FAILED','COMPLETED')
                """, wid);
    }

    private void syncInventory(Long warehouseId, String wid) {
        jdbc.update("""
                INSERT INTO handling_units
                  (warehouse_id, handling_unit_id, stock_id, item_id, item_name,
                   quantity, capacity, unit, home_rack_id, home_rack_level, status)
                SELECT ?, 'HU-BE-' || wi.warehouse_item_id,
                       'STOCK-BE-' || wi.warehouse_item_id,
                       wi.item_id::text, p.product_name,
                       wi.quantity, GREATEST(sl.max_quantity, wi.quantity),
                       'EA', wn.node_code, 1, 'stored'
                FROM warehouse_items wi
                JOIN storage_location sl ON sl.storage_location_id=wi.storage_location_id
                JOIN warehouse_node wn ON wn.node_id=wi.node_id
                LEFT JOIN product p ON p.product_id=wi.item_id
                WHERE wi.warehouse_id=?
                ON CONFLICT (warehouse_id, handling_unit_id) DO UPDATE
                SET quantity=EXCLUDED.quantity, item_name=EXCLUDED.item_name,
                    status=EXCLUDED.status, updated_at=now()
                """, wid, warehouseId);
    }

    private String syncOutboundTask(String wid, Long runId, Task task) {
        List<String> chutes = jdbc.query(
                "SELECT chute_id FROM outbound_chutes WHERE warehouse_id=? ORDER BY chute_id LIMIT 1",
                (rs, row) -> rs.getString(1), wid);
        if (chutes.isEmpty()) {
            return null;
        }
        String orderId = "ORD-%03d".formatted(task.getId());
        String operationId = "OP-RUN-" + runId + "-" + task.getId();
        String handlingUnitId = task.getWarehouseItem() == null
                ? null
                : "HU-BE-" + task.getWarehouseItem().getId();
        task.attachAiContract(
                operationId,
                orderId,
                null,
                "OUTBOUND_ORDER",
                handlingUnitId,
                "MEDIUM"
        );
        jdbc.update("""
                INSERT INTO orders
                  (warehouse_id, order_id, status, priority, outbound_chute_id)
                VALUES (?, ?, 'pending', 'medium', ?)
                ON CONFLICT (warehouse_id, order_id) DO NOTHING
                """, wid, orderId, chutes.get(0));
        jdbc.update("""
                INSERT INTO order_lines
                  (warehouse_id, order_id, line_no, item_id, required_qty)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT (warehouse_id, order_id, line_no) DO UPDATE
                SET required_qty=EXCLUDED.required_qty
                """, wid, orderId, String.valueOf(task.getEffectiveItemId()),
                task.effectiveQuantity());
        return orderId;
    }

    private String syncInboundTask(String wid, Long runId, Task task) {
        List<String> ports = jdbc.query(
                "SELECT port_id FROM inbound_ports WHERE warehouse_id=? ORDER BY port_id LIMIT 1",
                (rs, row) -> rs.getString(1), wid);
        if (ports.isEmpty()) {
            return null;
        }
        String inboundId = "IN-%03d".formatted(task.getId());
        String operationId = "OP-RUN-" + runId + "-" + task.getId();
        String handlingUnitId = "HU-IN-" + runId + "-" + task.getId();
        task.attachAiContract(
                operationId,
                null,
                inboundId,
                "INBOUND_RECEIPT",
                handlingUnitId,
                "MEDIUM"
        );
        jdbc.update("""
                INSERT INTO inbound_receipts
                  (warehouse_id, inbound_id, handling_unit_id, item_id, quantity,
                   source_port_id, target_rack_id, target_rack_level, status, priority)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', 'medium')
                ON CONFLICT (warehouse_id, inbound_id) DO UPDATE
                SET handling_unit_id=EXCLUDED.handling_unit_id,
                    item_id=EXCLUDED.item_id,
                    quantity=EXCLUDED.quantity,
                    source_port_id=EXCLUDED.source_port_id,
                    target_rack_id=EXCLUDED.target_rack_id,
                    target_rack_level=EXCLUDED.target_rack_level,
                    status='pending',
                    priority=EXCLUDED.priority,
                    updated_at=now()
                """, wid, inboundId,
                handlingUnitId,
                String.valueOf(task.getEffectiveItemId()), task.effectiveQuantity(),
                ports.get(0), null, null);
        return inboundId;
    }

    /**
     * Atomically accepts the putaway slot selected by LARO. The advisory lock
     * serializes competing plan installations for the same physical slot.
     */
    @Transactional
    public void confirmInboundPlacement(
            Task task,
            String targetRackId,
            Integer targetRackLevel,
            String deliveryNode
    ) {
        if (task.getInboundId() == null || targetRackId == null
                || targetRackLevel == null || deliveryNode == null) {
            throw new IllegalArgumentException(
                    "LARO inbound operation must contain target rack, level, and delivery node."
            );
        }
        String wid = AiRouteGraphSyncService.toAiWarehouseId(task.getWarehouse().getId());
        String slotKey = wid + ":" + targetRackId + ":" + targetRackLevel;
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", slotKey);

        Integer receiptQuantity = jdbc.query("""
                        SELECT quantity FROM inbound_receipts
                        WHERE warehouse_id=? AND inbound_id=?
                        FOR UPDATE
                        """,
                rs -> rs.next() ? rs.getInt(1) : null,
                wid, task.getInboundId());
        if (receiptQuantity == null) {
            throw new IllegalArgumentException(
                    "LARO inbound operation does not match a stored receipt: "
                            + wid + "/" + task.getInboundId()
            );
        }

        Boolean validSlot = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM rack_slots rs
                  WHERE rs.warehouse_id=? AND rs.rack_id=? AND rs.level=?
                    AND rs.status<>'FULL'
                    AND rs.capacity - COALESCE((
                      SELECT SUM(hu.quantity) FROM handling_units hu
                      WHERE hu.warehouse_id=rs.warehouse_id
                        AND hu.home_rack_id=rs.rack_id
                        AND hu.home_rack_level=rs.level
                        AND hu.status IN ('stored','reserved','in_transit','returning')
                    ), 0) - COALESCE((
                      SELECT SUM(other.quantity) FROM inbound_receipts other
                      WHERE other.warehouse_id=rs.warehouse_id
                        AND other.inbound_id<>?
                        AND other.target_rack_id=rs.rack_id
                        AND other.target_rack_level=rs.level
                        AND other.status IN ('planned','in_transit')
                    ), 0) >= ?
                )
                """, Boolean.class, wid, targetRackId, targetRackLevel,
                task.getInboundId(), receiptQuantity);
        if (!Boolean.TRUE.equals(validSlot)) {
            throw new IllegalArgumentException(
                    "LARO selected a missing, full, or undersized inbound slot: "
                            + wid + "/" + targetRackId + " level " + targetRackLevel
            );
        }

        Boolean validAccessNode = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM racks
                  WHERE warehouse_id=? AND rack_id=?
                    AND jsonb_exists(access_node_ids, ?)
                )
                """, Boolean.class, wid, targetRackId, deliveryNode);
        if (!Boolean.TRUE.equals(validAccessNode)) {
            throw new IllegalArgumentException(
                    "LARO delivery node does not belong to the selected rack: "
                            + deliveryNode + " -> " + targetRackId
            );
        }

        int updated = jdbc.update("""
                UPDATE inbound_receipts
                SET target_rack_id=?, target_rack_level=?, status='planned', updated_at=now()
                WHERE warehouse_id=? AND inbound_id=?
                """, targetRackId, targetRackLevel, wid, task.getInboundId());
        if (updated != 1) {
            throw new IllegalArgumentException(
                    "Failed to persist LARO inbound placement: "
                            + wid + "/" + task.getInboundId()
            );
        }
    }

    @Transactional
    public void syncTaskCompletion(Task task) {
        Long warehouseId = task.getWarehouse().getId();
        String wid = AiRouteGraphSyncService.toAiWarehouseId(warehouseId);

        if (task.getTaskType() == TaskType.OUTBOUND && task.getOrderId() != null) {
            syncInventory(warehouseId, wid);
            jdbc.update("""
                    UPDATE orders SET status='completed'
                    WHERE warehouse_id=? AND order_id=?
                    """, wid, task.getOrderId());
            return;
        }
        if (task.getTaskType() == TaskType.INBOUND && task.getInboundId() != null) {
            jdbc.update("""
                    INSERT INTO handling_units
                      (warehouse_id, handling_unit_id, stock_id, item_id, item_name,
                       quantity, capacity, unit, home_rack_id, home_rack_level, status)
                    SELECT ir.warehouse_id, ir.handling_unit_id,
                           'STOCK-' || ir.handling_unit_id, ir.item_id, p.product_name,
                           ir.quantity, rs.capacity, 'EA',
                           ir.target_rack_id, ir.target_rack_level, 'stored'
                    FROM inbound_receipts ir
                    JOIN rack_slots rs
                      ON rs.warehouse_id=ir.warehouse_id
                     AND rs.rack_id=ir.target_rack_id
                     AND rs.level=ir.target_rack_level
                    LEFT JOIN product p ON p.product_id::text=ir.item_id
                    WHERE ir.warehouse_id=? AND ir.inbound_id=?
                    ON CONFLICT (warehouse_id, handling_unit_id) DO UPDATE
                    SET quantity=EXCLUDED.quantity,
                        home_rack_id=EXCLUDED.home_rack_id,
                        home_rack_level=EXCLUDED.home_rack_level,
                        status='stored', updated_at=now()
                    """, wid, task.getInboundId());
            jdbc.update("""
                    UPDATE inbound_receipts
                    SET status='stored', updated_at=now()
                    WHERE warehouse_id=? AND inbound_id=?
                    """, wid, task.getInboundId());
            jdbc.update("""
                    UPDATE rack_slots rs
                    SET status=CASE
                      WHEN totals.quantity >= rs.capacity THEN 'FULL'
                      ELSE 'PARTIAL'
                    END
                    FROM (
                      SELECT warehouse_id, home_rack_id, home_rack_level,
                             SUM(quantity) AS quantity
                      FROM handling_units
                      WHERE warehouse_id=?
                      GROUP BY warehouse_id, home_rack_id, home_rack_level
                    ) totals
                    WHERE totals.warehouse_id=rs.warehouse_id
                      AND totals.home_rack_id=rs.rack_id
                      AND totals.home_rack_level=rs.level
                      AND rs.rack_id=(
                        SELECT target_rack_id FROM inbound_receipts
                        WHERE warehouse_id=? AND inbound_id=?
                      )
                      AND rs.level=(
                        SELECT target_rack_level FROM inbound_receipts
                        WHERE warehouse_id=? AND inbound_id=?
                      )
                    """, wid, wid, task.getInboundId(), wid, task.getInboundId());
        }
    }

    @Transactional
    public void releaseInboundPlacements(Long simulationRunId) {
        jdbc.update("""
                UPDATE inbound_receipts ir
                SET target_rack_id=NULL, target_rack_level=NULL,
                    status='pending', updated_at=now()
                FROM task t
                WHERE t.simulation_run_id=?
                  AND t.inbound_id=ir.inbound_id
                  AND ir.warehouse_id='WH-' || LPAD(t.warehouse_id::text, 3, '0')
                  AND ir.status IN ('planned','in_transit')
                """, simulationRunId);
    }

    private void syncStation(
            String wid,
            String stationId,
            List<String> accessIds,
            List<String> chutes
    ) {
        String robotId = "SR-" + stationId;
        jdbc.update("""
                INSERT INTO outbound_stations
                  (warehouse_id, station_id, station_robot_id, access_node_ids,
                   served_chute_ids, tote_buffer_capacity, status)
                VALUES (?, ?, ?, to_jsonb(?::text[]), to_jsonb(?::text[]), 20, 'available')
                ON CONFLICT (warehouse_id, station_id) DO UPDATE
                SET access_node_ids=EXCLUDED.access_node_ids,
                    served_chute_ids=EXCLUDED.served_chute_ids,
                    status=EXCLUDED.status
                """, wid, stationId, robotId, accessIds.toArray(String[]::new),
                chutes.toArray(String[]::new));
        jdbc.update("""
                INSERT INTO station_robots
                  (warehouse_id, station_robot_id, station_id, status,
                   max_orders_per_wave, items_per_tick)
                VALUES (?, ?, ?, 'available', 20, 1)
                ON CONFLICT (warehouse_id, station_robot_id) DO UPDATE
                SET status=EXCLUDED.status
                """, wid, robotId, stationId);
    }

    private void syncHandoff(
            String wid,
            String handoffId,
            List<String> accessIds
    ) {
        jdbc.update("""
                INSERT INTO inbound_handoffs
                  (warehouse_id, handoff_id, access_node_ids, buffer_capacity)
                VALUES (?, ?, to_jsonb(?::text[]), 20)
                ON CONFLICT (warehouse_id, handoff_id) DO UPDATE
                SET access_node_ids=EXCLUDED.access_node_ids
                """, wid, handoffId, accessIds.toArray(String[]::new));
        String portId = "PORT-" + handoffId;
        jdbc.update("""
                INSERT INTO inbound_ports (warehouse_id, port_id, label, handoff_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (warehouse_id, port_id) DO UPDATE
                SET handoff_id=EXCLUDED.handoff_id
                """, wid, portId, portId, handoffId);
    }

    private void normalizeFacilityStatuses(String wid) {
        jdbc.update(
                "UPDATE outbound_stations SET status='available' WHERE warehouse_id=?",
                wid
        );
        jdbc.update(
                "UPDATE station_robots SET status='available' WHERE warehouse_id=?",
                wid
        );
        jdbc.update(
                "UPDATE empty_tote_buffers SET status='available' WHERE warehouse_id=?",
                wid
        );
    }
}
