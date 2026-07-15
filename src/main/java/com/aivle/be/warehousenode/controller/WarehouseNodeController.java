package com.aivle.be.warehousenode.controller;

import com.aivle.be.warehousenode.dto.WarehouseNodeCreateRequest;
import com.aivle.be.warehousenode.dto.WarehouseNodeResponse;
import com.aivle.be.warehousenode.dto.WarehouseNodeUpdateRequest;
import com.aivle.be.warehousenode.service.WarehouseNodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/warehouse-nodes")
@RequiredArgsConstructor
public class WarehouseNodeController {

    private final WarehouseNodeService warehouseNodeService;

    @PostMapping
    public ResponseEntity<WarehouseNodeResponse> createNode(
            @RequestBody WarehouseNodeCreateRequest request
    ) {
        WarehouseNodeResponse response =
                warehouseNodeService.createNode(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{nodeId}")
    public ResponseEntity<WarehouseNodeResponse> getNode(
            @PathVariable Long nodeId
    ) {
        return ResponseEntity.ok(
                warehouseNodeService.getNode(nodeId)
        );
    }

    @GetMapping
    public ResponseEntity<List<WarehouseNodeResponse>> getNodes() {
        return ResponseEntity.ok(
                warehouseNodeService.getNodes()
        );
    }

    @PatchMapping("/{nodeId}")
    public ResponseEntity<WarehouseNodeResponse> updateNode(
            @PathVariable Long nodeId,
            @RequestBody WarehouseNodeUpdateRequest request
    ) {
        return ResponseEntity.ok(
                warehouseNodeService.updateNode(nodeId, request)
        );
    }

    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> deleteNode(
            @PathVariable Long nodeId
    ) {
        warehouseNodeService.deleteNode(nodeId);

        return ResponseEntity.noContent().build();
    }
}