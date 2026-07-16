package com.aivle.be.warehousezone.controller;

import com.aivle.be.warehousezone.dto.WarehouseZoneCreateRequest;
import com.aivle.be.warehousezone.dto.WarehouseZoneResponse;
import com.aivle.be.warehousezone.dto.WarehouseZoneUpdateRequest;
import com.aivle.be.warehousezone.service.WarehouseZoneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouse-zones")
public class WarehouseZoneController {

    private final WarehouseZoneService warehouseZoneService;

    @PostMapping
    public ResponseEntity<WarehouseZoneResponse> createZone(
            @RequestBody WarehouseZoneCreateRequest request
    ) {
        WarehouseZoneResponse response =
                warehouseZoneService.createZone(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{zoneId}")
    public ResponseEntity<WarehouseZoneResponse> getZone(
            @PathVariable Long zoneId
    ) {
        return ResponseEntity.ok(
                warehouseZoneService.getZone(zoneId)
        );
    }

    @GetMapping
    public ResponseEntity<List<WarehouseZoneResponse>> getZones() {
        return ResponseEntity.ok(
                warehouseZoneService.getZones()
        );
    }

    @PatchMapping("/{zoneId}")
    public ResponseEntity<WarehouseZoneResponse> updateZone(
            @PathVariable Long zoneId,
            @RequestBody WarehouseZoneUpdateRequest request
    ) {
        return ResponseEntity.ok(
                warehouseZoneService.updateZone(zoneId, request)
        );
    }

    @DeleteMapping("/{zoneId}")
    public ResponseEntity<Void> deleteZone(
            @PathVariable Long zoneId
    ) {
        warehouseZoneService.deleteZone(zoneId);
        return ResponseEntity.noContent().build();
    }
}