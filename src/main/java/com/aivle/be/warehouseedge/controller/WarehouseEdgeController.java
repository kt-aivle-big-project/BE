package com.aivle.be.warehouseedge.controller;

import com.aivle.be.warehouseedge.dto.WarehouseEdgeCreateRequest;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeResponse;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeUpdateRequest;
import com.aivle.be.warehouseedge.service.WarehouseEdgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/warehouse-edges")
public class WarehouseEdgeController {

    private final WarehouseEdgeService warehouseEdgeService;

    @PostMapping
    public ResponseEntity<WarehouseEdgeResponse> createEdge(
            @RequestBody WarehouseEdgeCreateRequest request
    ) {
        System.out.println(
                "fromNodeId=" + request.getFromNodeId()
                        + ", toNodeId=" + request.getToNodeId()
                        + ", distance=" + request.getDistance()
                        + ", directionType=" + request.getDirectionType()
        );

        WarehouseEdgeResponse response = warehouseEdgeService.createEdge(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{edgeId}")
    public ResponseEntity<WarehouseEdgeResponse> getEdge(
            @PathVariable Long edgeId
    ) {
        return ResponseEntity.ok(warehouseEdgeService.getEdge(edgeId));
    }

    @GetMapping
    public ResponseEntity<List<WarehouseEdgeResponse>> getEdges() {
        return ResponseEntity.ok(warehouseEdgeService.getEdges());
    }

    @PatchMapping("/{edgeId}")
    public ResponseEntity<WarehouseEdgeResponse> updateEdge(
            @PathVariable Long edgeId,
            @RequestBody WarehouseEdgeUpdateRequest request
    ) {
        return ResponseEntity.ok(
                warehouseEdgeService.updateEdge(edgeId, request)
        );
    }

    @DeleteMapping("/{edgeId}")
    public ResponseEntity<Void> deleteEdge(
            @PathVariable Long edgeId
    ) {
        warehouseEdgeService.deleteEdge(edgeId);
        return ResponseEntity.noContent().build();
    }
}