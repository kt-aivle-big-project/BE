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
        List<Map<String, Object>> targets = jdbc.queryForList("""
                SELECT p.port_id, r.rack_id
                FROM inbound_ports p CROSS JOIN racks r
                WHERE p.warehouse_id=? AND r.warehouse_id=?
                ORDER BY p.port_id, r.rack_id LIMIT 1
                """, wid, wid);
        if (targets.isEmpty()) {
            return null;
        }
        Map<String, Object> target = targets.get(0);
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
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'pending', 'medium')
                ON CONFLICT (warehouse_id, inbound_id) DO NOTHING
                """, wid, inboundId,
                handlingUnitId,
                String.valueOf(task.getEffectiveItemId()), task.effectiveQuantity(),
                target.get("port_id"), target.get("rack_id"));
        return inboundId;
    }

    @Transactional
    public void syncTaskCompletion(Task task) {
        Long warehouseId = task.getWarehouse().getId();
        String wid = AiRouteGraphSyncService.toAiWarehouseId(warehouseId);
        syncInventory(warehouseId, wid);

        if (task.getTaskType() == TaskType.OUTBOUND && task.getOrderId() != null) {
            jdbc.update("""
                    UPDATE orders SET status='completed'
                    WHERE warehouse_id=? AND order_id=?
                    """, wid, task.getOrderId());
            return;
        }
        if (task.getTaskType() == TaskType.INBOUND && task.getInboundId() != null) {
            jdbc.update("""
                    UPDATE inbound_receipts
                    SET status='stored', updated_at=now()
                    WHERE warehouse_id=? AND inbound_id=?
                    """, wid, task.getInboundId());
        }
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
