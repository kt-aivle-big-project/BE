package com.aivle.be.warehouse.controller;

import java.util.List;
import com.aivle.be.warehouse.dto.WarehouseUpdateRequest;
import com.aivle.be.warehouse.dto.WarehouseCreateRequest;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @PostMapping
    public ResponseEntity<WarehouseResponse> createWarehouse(
            @RequestBody WarehouseCreateRequest request
    ) {
        WarehouseResponse response = warehouseService.createWarehouse(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
    @GetMapping("/{warehouseId}")
    public ResponseEntity<WarehouseResponse> getWarehouse(
            @PathVariable Long warehouseId
    ) {
        WarehouseResponse response = warehouseService.getWarehouse(warehouseId);

        return ResponseEntity.ok(response);
    }
    @GetMapping
    public ResponseEntity<List<WarehouseResponse>> getWarehouses() {
        List<WarehouseResponse> responses = warehouseService.getWarehouses();

        return ResponseEntity.ok(responses);
    }
    @PatchMapping("/{warehouseId}")
    public ResponseEntity<WarehouseResponse> updateWarehouse(
            @PathVariable Long warehouseId,
            @RequestBody WarehouseUpdateRequest request
    ) {
        WarehouseResponse response =
                warehouseService.updateWarehouse(warehouseId, request);

        return ResponseEntity.ok(response);
    }
    @DeleteMapping("/{warehouseId}")
    public ResponseEntity<Void> deleteWarehouse(
            @PathVariable Long warehouseId
    ) {
        warehouseService.deleteWarehouse(warehouseId);

        return ResponseEntity.noContent().build();
    }
}