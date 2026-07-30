package com.aivle.be.optimization.controller;

import com.aivle.be.optimization.dto.request.LaroPlanRequest;
import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import com.aivle.be.optimization.service.LaroPlanningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/laro")
@RequiredArgsConstructor
public class LaroPlanningController {

    private final LaroPlanningService laroPlanningService;

    @PostMapping("/warehouses/{warehouseId}/missions/plan")
    public ResponseEntity<LaroPlanResponse> createPlan(
            @PathVariable String warehouseId,
            @RequestBody LaroPlanRequest request
    ) {
        return ResponseEntity.ok(
                laroPlanningService.createPlan(
                        warehouseId,
                        request
                )
        );
    }
}
