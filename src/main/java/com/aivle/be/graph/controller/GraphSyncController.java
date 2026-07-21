package com.aivle.be.graph.controller;

import com.aivle.be.graph.service.GraphSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/graph-sync")
@RequiredArgsConstructor
public class GraphSyncController {

    private final GraphSyncService graphSyncService;

    @PostMapping("/warehouses/{warehouseId}/nodes")
    public ResponseEntity<Map<String, Object>> syncNodes(
            @PathVariable Long warehouseId
    ) {
        int syncedCount = graphSyncService.syncNodes(warehouseId);

        return ResponseEntity.ok(
                Map.of(
                        "warehouseId", warehouseId,
                        "syncedNodeCount", syncedCount
                )
        );
    }
    @PostMapping("/warehouses/{warehouseId}/edges")
    public ResponseEntity<Map<String, Object>> syncEdges(
            @PathVariable Long warehouseId
    ) {
        int syncedCount = graphSyncService.syncEdges(warehouseId);

        return ResponseEntity.ok(
                Map.of(
                        "warehouseId", warehouseId,
                        "syncedEdgeCount", syncedCount
                )
        );
    }
    @PostMapping("/warehouses/{warehouseId}")
    public ResponseEntity<Map<String, Object>> syncWarehouseGraph(
            @PathVariable Long warehouseId
    ) {
        Map<String, Integer> result =
                graphSyncService.syncWarehouseGraph(warehouseId);

        return ResponseEntity.ok(
                Map.of(
                        "warehouseId", warehouseId,
                        "syncedNodeCount", result.get("syncedNodeCount"),
                        "syncedEdgeCount", result.get("syncedEdgeCount")
                )
        );
    }
}