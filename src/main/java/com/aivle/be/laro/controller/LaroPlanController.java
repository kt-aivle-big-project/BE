package com.aivle.be.laro.controller;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.service.LaroPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "LARO Plan", description = "기존 BE SimulationRun 기반 LARO 계획 API")
@RestController
@RequestMapping("/api/laro/simulation-runs")
public class LaroPlanController {
    private final LaroPlanService service;

    public LaroPlanController(LaroPlanService service) {
        this.service = service;
    }

    @Operation(summary = "LARO 계획 전 PostgreSQL·Redis·Neo4j 계약 점검")
    @GetMapping("/{simulationRunId}/plan/preflight")
    public ResponseEntity<LaroPreflightResponse> preflight(
            @PathVariable Long simulationRunId
    ) {
        return ResponseEntity.ok(service.preflight(simulationRunId));
    }

    @Operation(summary = "구조화 업무와 선택적 자연어 명령으로 LARO 계획 생성")
    @PostMapping("/{simulationRunId}/plan")
    public ResponseEntity<LaroPlanResponse> plan(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody LaroPlanRequest request
    ) {
        return ResponseEntity.ok(service.plan(simulationRunId, request));
    }

    @Operation(summary = "안전 노드 정지와 작업 완료 장벽을 적용한 LARO 재계획")
    @PostMapping("/{simulationRunId}/replan")
    public ResponseEntity<LaroPlanResponse> replan(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody LaroPlanRequest request
    ) {
        return ResponseEntity.ok(service.replan(simulationRunId, request));
    }
}
