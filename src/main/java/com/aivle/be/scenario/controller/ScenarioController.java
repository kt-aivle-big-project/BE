package com.aivle.be.scenario.controller;

import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.scenario.controller.request.ScenarioRequest;
import com.aivle.be.scenario.controller.request.ScenarioUpdateRequest;
import com.aivle.be.scenario.controller.response.ScenarioResponse;
import com.aivle.be.scenario.service.ScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Scenario", description = "시뮬레이션 시나리오 프리셋 관리 API")
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final AuthenticatedRequesterResolver requesterResolver;
    private final GuestAccessPolicy guestAccessPolicy;

    @Operation(summary = "시나리오 생성")
    @PostMapping
    public ResponseEntity<ScenarioResponse> create(
            @Valid @RequestBody ScenarioRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scenarioService.create(
                        request,
                        requesterResolver.resolve(authentication)
                ));
    }

    @Operation(summary = "시나리오 단건 조회")
    @GetMapping("/{scenarioId}")
    public ResponseEntity<ScenarioResponse> get(
            @PathVariable Long scenarioId,
            Authentication authentication
    ) {
        guestAccessPolicy.validateScenarioRead(
                requesterResolver.resolve(authentication),
                scenarioId
        );
        return ResponseEntity.ok(scenarioService.get(scenarioId));
    }

    @Operation(summary = "시나리오 목록 조회 (warehouseId 지정 시 창고별)")
    @GetMapping
    public ResponseEntity<List<ScenarioResponse>> getAll(
            @RequestParam(required = false) Long warehouseId
    ) {
        return ResponseEntity.ok(scenarioService.getAll(warehouseId));
    }

    @Operation(summary = "시나리오 설정 저장")
    @PatchMapping("/{scenarioId}")
    public ResponseEntity<ScenarioResponse> update(
            @PathVariable Long scenarioId,
            @Valid @RequestBody ScenarioUpdateRequest request
    ) {
        return ResponseEntity.ok(scenarioService.update(scenarioId, request));
    }

    @Operation(summary = "시나리오 삭제")
    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<Void> delete(@PathVariable Long scenarioId) {
        scenarioService.delete(scenarioId);
        return ResponseEntity.noContent().build();
    }
}
