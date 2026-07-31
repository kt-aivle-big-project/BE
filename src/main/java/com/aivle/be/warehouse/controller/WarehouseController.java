package com.aivle.be.warehouse.controller;

import java.util.List;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.warehouse.dto.WarehouseLayoutResponse;
import com.aivle.be.warehouse.service.WarehouseLayoutService;
import com.aivle.be.warehouse.dto.WarehouseUpdateRequest;
import com.aivle.be.warehouse.dto.WarehouseCreateRequest;
import com.aivle.be.warehouse.dto.WarehouseGraphResponse;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.service.WarehouseGraphService;
import com.aivle.be.warehouse.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final WarehouseLayoutService warehouseLayoutService;
    private final WarehouseGraphService warehouseGraphService;
    private final AuthenticatedRequesterResolver requesterResolver;
    private final GuestAccessPolicy guestAccessPolicy;

    /**
     * 창고 그래프(맵) 전체를 내려준다.
     *
     * 프론트 화면과 AI(cuOpt/MAPF)가 같은 맵을 바라보게 하기 위한 창구다.
     * 노드·간선을 숫자 PK 가 아니라 코드(R0_0, H0_0)로 내보낸다.
     */
    @GetMapping("/{warehouseId}/graph")
    public ResponseEntity<WarehouseGraphResponse> getGraph(
            @PathVariable Long warehouseId,
            Authentication authentication
    ) {
        validateReadAccess(authentication, warehouseId);
        return ResponseEntity.ok(warehouseGraphService.getGraph(warehouseId));
    }

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
            @PathVariable Long warehouseId,
            Authentication authentication
    ) {
        validateReadAccess(authentication, warehouseId);
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
    @GetMapping("/{warehouseId}/layout")
    public ResponseEntity<WarehouseLayoutResponse> getWarehouseLayout(
            @PathVariable Long warehouseId,
            Authentication authentication
    ) {
        validateReadAccess(authentication, warehouseId);
        return ResponseEntity.ok(
                warehouseLayoutService.getLayout(warehouseId)
        );
    }

    private void validateReadAccess(Authentication authentication, Long warehouseId) {
        guestAccessPolicy.validateWarehouseRead(
                requesterResolver.resolve(authentication),
                warehouseId
        );
    }
}
