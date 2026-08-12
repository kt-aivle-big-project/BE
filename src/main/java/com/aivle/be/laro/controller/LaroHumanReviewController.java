package com.aivle.be.laro.controller;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.laro.dto.LaroHumanReviewRequest;
import com.aivle.be.laro.dto.LaroHumanReviewRetryRequest;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleStatusResponse;
import com.aivle.be.simulationrun.service.SimulationRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "LARO Human Review", description = "시뮬레이션 실행에 귀속된 Human Review 처리 API")
@RestController
@RequestMapping("/api/laro/simulation-runs")
public class LaroHumanReviewController {

    private final SimulationCommandCycleService commandCycleService;
    private final SimulationRunService simulationRunService;
    private final AuthenticatedRequesterResolver requesterResolver;

    public LaroHumanReviewController(
            SimulationCommandCycleService commandCycleService,
            SimulationRunService simulationRunService,
            AuthenticatedRequesterResolver requesterResolver
    ) {
        this.commandCycleService = commandCycleService;
        this.simulationRunService = simulationRunService;
        this.requesterResolver = requesterResolver;
    }

    @Operation(summary = "Human Review 응답 후 계획 재개·보류·종료 처리")
    @PostMapping("/{simulationRunId}/human-reviews/{interactionId}/respond")
    public ResponseEntity<SimulationCommandCycleStatusResponse> respond(
            @PathVariable Long simulationRunId,
            @PathVariable String interactionId,
            @Valid @RequestBody LaroHumanReviewRequest request,
            Authentication authentication
    ) {
        AuthenticatedRequester requester = requesterResolver.resolve(authentication);
        simulationRunService.validateOwnership(simulationRunId, requester);
        String actorId = requester.isUser()
                ? "USER-" + requester.userId()
                : "GUEST-" + requester.guestSessionId();
        return ResponseEntity.ok(
                commandCycleService.respondToHumanReview(
                        simulationRunId,
                        interactionId,
                        request,
                        actorId
                )
        );
    }

    @Operation(summary = "외부 조치 완료 후 최신 사실로 새 계획 사이클 시작")
    @PostMapping("/{simulationRunId}/human-reviews/{interactionId}/retry")
    public ResponseEntity<SimulationCommandCycleStatusResponse> retry(
            @PathVariable Long simulationRunId,
            @PathVariable String interactionId,
            @Valid @RequestBody LaroHumanReviewRetryRequest request,
            Authentication authentication
    ) {
        AuthenticatedRequester requester = requesterResolver.resolve(authentication);
        simulationRunService.validateOwnership(simulationRunId, requester);
        return ResponseEntity.accepted().body(
                commandCycleService.retryAfterHumanAction(
                        simulationRunId,
                        interactionId,
                        request.executionVersion()
                )
        );
    }
}
