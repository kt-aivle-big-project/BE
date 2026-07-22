package com.aivle.be.simulation.controller;

import com.aivle.be.simulation.controller.request.SimulationAgentInteractionRequest;
import com.aivle.be.simulation.controller.request.SimulationCompleteRequest;
import com.aivle.be.simulation.controller.request.SimulationCreateRequest;
import com.aivle.be.simulation.controller.request.SimulationPathUpdateRequest;
import com.aivle.be.simulation.controller.request.SimulationPolicyResultRequest;
import com.aivle.be.simulation.controller.response.PathOverlapResponse;
import com.aivle.be.simulation.controller.response.SimulationResponse;
import com.aivle.be.simulation.service.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Simulation", description = "시뮬레이션 실행 기록 및 진행 상태 관리 API")
@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @Operation(summary = "시뮬레이션 생성 (= 시작)")
    @ApiResponse(responseCode = "200", description = "생성 성공")
    @ApiResponse(responseCode = "404", description = "창고를 찾을 수 없음")
    @PostMapping
    public ResponseEntity<SimulationResponse> createSimulation(@RequestBody SimulationCreateRequest request) {
        return ResponseEntity.ok(simulationService.createSimulation(request));
    }

    @Operation(summary = "시뮬레이션 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "시뮬레이션을 찾을 수 없음")
    @GetMapping("/{simulationId}")
    public ResponseEntity<SimulationResponse> getSimulation(@PathVariable Long simulationId) {
        return ResponseEntity.ok(simulationService.getSimulation(simulationId));
    }

    @Operation(summary = "시뮬레이션 전체 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public ResponseEntity<List<SimulationResponse>> getAllSimulations() {
        return ResponseEntity.ok(simulationService.getAllSimulations());
    }

    @Operation(summary = "에이전트 상호작용 기록 (LLM 입출력, 토큰, 지연시간 등)")
    @ApiResponse(responseCode = "200", description = "기록 성공")
    @ApiResponse(responseCode = "404", description = "시뮬레이션을 찾을 수 없음")
    @PatchMapping("/{simulationId}/agent-interaction")
    public ResponseEntity<SimulationResponse> recordAgentInteraction(
            @PathVariable Long simulationId,
            @RequestBody SimulationAgentInteractionRequest request
    ) {
        return ResponseEntity.ok(simulationService.recordAgentInteraction(simulationId, request));
    }

    @Operation(summary = "정책 판단 결과 기록")
    @ApiResponse(responseCode = "200", description = "기록 성공")
    @ApiResponse(responseCode = "404", description = "시뮬레이션을 찾을 수 없음")
    @PatchMapping("/{simulationId}/policy-result")
    public ResponseEntity<SimulationResponse> recordPolicyResult(
            @PathVariable Long simulationId,
            @RequestBody SimulationPolicyResultRequest request
    ) {
        return ResponseEntity.ok(simulationService.recordPolicyResult(simulationId, request));
    }

    @Operation(summary = "시뮬레이션 종료 처리")
    @ApiResponse(responseCode = "200", description = "종료 처리 성공")
    @ApiResponse(responseCode = "404", description = "시뮬레이션을 찾을 수 없음")
    @PatchMapping("/{simulationId}/complete")
    public ResponseEntity<SimulationResponse> completeSimulation(
            @PathVariable Long simulationId,
            @RequestBody SimulationCompleteRequest request
    ) {
        return ResponseEntity.ok(simulationService.completeSimulation(simulationId, request));
    }

    @Operation(summary = "시뮬레이션 경로 갱신")
    @ApiResponse(responseCode = "200", description = "갱신 성공")
    @ApiResponse(responseCode = "404", description = "시뮬레이션을 찾을 수 없음")
    @PatchMapping("/{simulationId}/path")
    public ResponseEntity<SimulationResponse> updatePath(
            @PathVariable Long simulationId,
            @RequestBody SimulationPathUpdateRequest request
    ) {
        return ResponseEntity.ok(simulationService.updatePath(simulationId, request));
    }

    @Operation(summary = "특정 노드가 진행 중인 시뮬레이션 경로와 겹치는지 확인 (장애물/차단 발생 시 재계산 필요 여부 판단용)")
    @ApiResponse(responseCode = "200", description = "판단 성공")
    @GetMapping("/path-overlap")
    public ResponseEntity<PathOverlapResponse> checkPathOverlap(@RequestParam Long nodeId) {
        return ResponseEntity.ok(simulationService.checkPathOverlap(nodeId));
    }
}