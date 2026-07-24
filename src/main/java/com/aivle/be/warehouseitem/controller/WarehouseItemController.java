package com.aivle.be.warehouseitem.controller;

import com.aivle.be.warehouseitem.dto.WarehouseItemRequest;
import com.aivle.be.warehouseitem.dto.WarehouseItemResponse;
import com.aivle.be.warehouseitem.service.WarehouseItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Warehouse Item", description = "창고 재고·품목 관리 API")
@RestController
@RequestMapping("/api/warehouse-items")
@RequiredArgsConstructor
public class WarehouseItemController {

    private final WarehouseItemService warehouseItemService;

    @Operation(summary = "창고 품목 등록")
    @PostMapping
    public ResponseEntity<WarehouseItemResponse> create(
            @Valid @RequestBody WarehouseItemRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(warehouseItemService.create(request));
    }

    @Operation(summary = "창고 품목 단건 조회")
    @GetMapping("/{warehouseItemId}")
    public ResponseEntity<WarehouseItemResponse> get(@PathVariable Long warehouseItemId) {
        return ResponseEntity.ok(warehouseItemService.get(warehouseItemId));
    }

    @Operation(summary = "창고 품목 목록 조회 (warehouseId 지정 시 해당 창고만)")
    @GetMapping
    public ResponseEntity<List<WarehouseItemResponse>> getAll(
            @RequestParam(required = false) Long warehouseId
    ) {
        if (warehouseId != null) {
            return ResponseEntity.ok(warehouseItemService.getByWarehouse(warehouseId));
        }
        return ResponseEntity.ok(warehouseItemService.getAll());
    }

    @Operation(summary = "창고 품목 수정")
    @PatchMapping("/{warehouseItemId}")
    public ResponseEntity<WarehouseItemResponse> update(
            @PathVariable Long warehouseItemId,
            @Valid @RequestBody WarehouseItemRequest request
    ) {
        return ResponseEntity.ok(warehouseItemService.update(warehouseItemId, request));
    }

    @Operation(summary = "창고 품목 삭제")
    @DeleteMapping("/{warehouseItemId}")
    public ResponseEntity<Void> delete(@PathVariable Long warehouseItemId) {
        warehouseItemService.delete(warehouseItemId);
        return ResponseEntity.noContent().build();
    }
}