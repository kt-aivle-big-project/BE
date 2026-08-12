package com.aivle.be.fulfillmentcommand.controller;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandGenerationService;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Fulfillment Command", description = "재고·빈 보관 위치 기반 입고/출고 BOX 명령 생성 API")
@RestController
@RequestMapping("/api/simulation-runs")
@RequiredArgsConstructor
public class FulfillmentCommandController {

    private final FulfillmentCommandGenerationService service;
    private final SimulationCommandCycleService commandCycleService;

    @Operation(summary = "plan 입력과 프론트 표시용 입고/출고 명령 생성")
    @PostMapping("/{simulationRunId}/fulfillment-commands/generate")
    public ResponseEntity<FulfillmentCommandGenerateResponse> generate(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody(required = false) FulfillmentCommandGenerateRequest request
    ) {
        return ResponseEntity.ok(request == null
                ? service.generate(simulationRunId)
                : service.generate(simulationRunId, request));
    }

    @Operation(summary = "설정된 주기별 자동 명령 생성 및 AI 계획 상태 조회")
    @GetMapping("/{simulationRunId}/command-cycle")
    public ResponseEntity<SimulationCommandCycleStatusResponse> commandCycleStatus(
            @PathVariable Long simulationRunId
    ) {
        return ResponseEntity.ok(commandCycleService.status(simulationRunId));
    }

    @Operation(summary = "다음 자동 재계획 경계를 기다리지 않고 명령 생성 및 AI 계획 즉시 실행")
    @PostMapping("/{simulationRunId}/command-cycle/trigger")
    public ResponseEntity<SimulationCommandCycleStatusResponse> triggerCommandCycle(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody(required = false) FulfillmentCommandGenerateRequest request
    ) {
        return ResponseEntity.accepted().body(
                commandCycleService.triggerNow(simulationRunId, request)
        );
    }

    @Operation(summary = "자동 계획 주기·평균 작업량·AI 입력 표현 방식 저장")
    @PutMapping("/{simulationRunId}/command-cycle/configuration")
    public ResponseEntity<SimulationCommandCycleStatusResponse> configureCommandCycle(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody FulfillmentCommandGenerateRequest request
    ) {
        return ResponseEntity.ok(commandCycleService.configure(simulationRunId, request));
    }
}
