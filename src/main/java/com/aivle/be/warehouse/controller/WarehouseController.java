package com.aivle.be.warehouse.controller;

import java.util.List;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final WarehouseLayoutService warehouseLayoutService;
    private final WarehouseGraphService warehouseGraphService;
    private final WarehouseImportService warehouseImportService;

    /**
     * 창고 그래프(맵) 전체를 내려준다.
     *
     * 프론트 화면과 AI(cuOpt/MAPF)가 같은 맵을 바라보게 하기 위한 창구다.
     * 노드·간선을 숫자 PK 가 아니라 코드(R0_0, H0_0)로 내보낸다.
     */
    @GetMapping("/{warehouseId}/graph")
    public ResponseEntity<WarehouseGraphResponse> getGraph(
            @PathVariable Long warehouseId
    ) {
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

    /**
     * 지도 JSON 으로 창고를 만든다.
     *
     * <p>일반 생성({@code POST /api/warehouses})은 이름·크기만 저장해
     * 노드가 없는 빈 창고가 된다. 시뮬레이션을 돌리려면 지도가 필요하므로
     * 화면에서 창고를 추가할 때는 이 엔드포인트를 쓴다.
     *
     * <p>노드·간선뿐 아니라 랙·충전소·보관위치·로봇·시나리오까지 함께 만들어진다.
     */
    @PostMapping("/import")
    public ResponseEntity<WarehouseImportResponse> importWarehouse(
            @Valid @RequestBody WarehouseImportRequest request,
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(warehouseImportService.importWarehouse(request, parseUserId(userId)));
    }

    /**
     * 인증 정보에서 사용자 ID 를 꺼낸다. 없으면 null 로 두고 요청 값을 쓴다.
     */
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
            @PathVariable Long warehouseId
    ) {
        WarehouseResponse response = warehouseService.getWarehouse(warehouseId);

        return ResponseEntity.ok(response);
    }
    /**
     * 볼 수 있는 창고 목록.
     * 공용 창고와 본인이 만든 창고만 나온다.
     */
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
            @PathVariable Long warehouseId
    ) {
        return ResponseEntity.ok(
                warehouseLayoutService.getLayout(warehouseId)
        );
    }
}