package com.aivle.be.optimization.controller;

import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.service.OptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/optimizations")
@RequiredArgsConstructor
public class OptimizationController {

    private final OptimizationService optimizationService;

    @PostMapping
    public ResponseEntity<OptimizationResponse> optimize(
            @RequestBody OptimizationRequest request
    ) {
        OptimizationResponse response =
                optimizationService.optimize(request);

        return ResponseEntity.ok(response);
    }
}