package com.aivle.be.warehouse.controller;

import java.util.List;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.warehouse.dto.WarehouseLayoutResponse;
import com.aivle.be.warehouse.service.WarehouseLayoutService;
import com.aivle.be.warehouse.dto.WarehouseUpdateRequest;
import com.aivle.be.warehouse.dto.WarehouseCreateRequest;
import com.aivle.be.warehouse.dto.WarehouseGraphResponse;
import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import com.aivle.be.warehouse.dto.WarehouseImportResponse;
import com.aivle.be.warehouse.service.WarehouseImportService;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.service.WarehouseGraphService;
import com.aivle.be.warehouse.service.WarehouseService;
import com.aivle.be.warehouse.service.WarehouseTemplateCloneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final WarehouseImportService warehouseImportService;
    private final WarehouseTemplateCloneService warehouseTemplateCloneService;

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

    @PostMapping("/{templateWarehouseId}/personal-copy")
    public ResponseEntity<WarehouseResponse> ensurePersonalCopy(
            @PathVariable Long templateWarehouseId,
            Authentication authentication
    ) {
        var requester = requesterResolver.resolve(authentication);
        guestAccessPolicy.requireUser(requester);
        return ResponseEntity.ok(WarehouseResponse.from(
                warehouseTemplateCloneService.ensurePersonalCopy(
                        templateWarehouseId,
                        requester.userId()
                )
        ));
    }

    @PostMapping("/{templateWarehouseId}/guest-personal-copy")
    public ResponseEntity<WarehouseResponse> ensureGuestPersonalCopy(
            @PathVariable Long templateWarehouseId,
            Authentication authentication
    ) {
        var requester = requesterResolver.resolve(authentication);
        guestAccessPolicy.requireGuest(requester);
        return ResponseEntity.ok(WarehouseResponse.from(
                warehouseTemplateCloneService.ensureGuestPersonalCopy(
                        templateWarehouseId,
                        requester.guestSessionId()
                )
        ));
    }

    @PostMapping("/import")
    public ResponseEntity<WarehouseImportResponse> importWarehouse(
            @Valid @RequestBody WarehouseImportRequest request,
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(warehouseImportService.importWarehouse(request, parseUserId(userId)));
    }

    private Long parseUserId(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(principal);
        } catch (NumberFormatException exception) {
            return null;
        }
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
    public ResponseEntity<List<WarehouseResponse>> getWarehouses(
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.ok(
                warehouseService.getWarehouses(parseUserId(userId))
        );
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

    @PutMapping("/{warehouseId}/layout")
    public ResponseEntity<WarehouseImportResponse> updateWarehouseLayout(
            @PathVariable Long warehouseId,
            @Valid @RequestBody WarehouseImportRequest request
    ) {
        return ResponseEntity.ok(
                warehouseImportService.updateWarehouseLayout(warehouseId, request)
        );
    }

    private void validateReadAccess(Authentication authentication, Long warehouseId) {
        guestAccessPolicy.validateWarehouseRead(
                requesterResolver.resolve(authentication),
                warehouseId
        );
    }
}
