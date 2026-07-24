package com.aivle.be.optimization.controller;

import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.OptimizationResponse;
import com.aivle.be.optimization.dto.response.OptimizationResultResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationHistoryResponse;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.service.OptimizationService;
import com.aivle.be.optimization.service.ReoptimizationQueryService;
import com.aivle.be.optimization.service.ReoptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/optimizations")
@RequiredArgsConstructor
public class OptimizationController {

    private final OptimizationService optimizationService;
    private final ReoptimizationService reoptimizationService;
    private final ReoptimizationQueryService reoptimizationQueryService;

    @PostMapping
    public ResponseEntity<OptimizationResponse> optimize(
            @RequestBody OptimizationRequest request
    ) {
        OptimizationResponse response =
                optimizationService.optimize(request);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/results/{resultId}")
    public ResponseEntity<OptimizationResultResponse> getResult(
            @PathVariable Long resultId
    ) {
        return ResponseEntity.ok(
                optimizationService.getResult(resultId)
        );
    }

    @GetMapping("/results/request/{requestId}")
    public ResponseEntity<OptimizationResultResponse> getResultByRequestId(
            @PathVariable String requestId
    ) {
        return ResponseEntity.ok(
                optimizationService.getResultByRequestId(requestId)
        );
    }

    @PostMapping("/simulation-runs/{simulationRunId}/reoptimize")
    public ResponseEntity<ReoptimizationResponse> reoptimize(
            @PathVariable Long simulationRunId,
            @RequestBody ReoptimizationRequest request
    ) {
        return ResponseEntity.ok(
                reoptimizationService.reoptimize(
                        simulationRunId,
                        request
                )
        );
    }

    @GetMapping("/simulation-runs/{simulationRunId}/reoptimization-histories")
    public ResponseEntity<List<ReoptimizationHistoryResponse>>
    getReoptimizationHistories(
            @PathVariable Long simulationRunId
    ) {
        return ResponseEntity.ok(
                reoptimizationQueryService.getHistories(
                        simulationRunId
                )
        );
    }
}