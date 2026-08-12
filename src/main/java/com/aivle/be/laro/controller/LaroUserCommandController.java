package com.aivle.be.laro.controller;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.laro.dto.LaroUserCommandRequest;
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

@Tag(name = "LARO User Command", description = "사용자 명령을 기존 자동 명령 사이클에 주입하는 API")
@RestController
@RequestMapping("/api/laro/simulation-runs")
public class LaroUserCommandController {

    private final SimulationCommandCycleService commandCycleService;
    private final SimulationRunService simulationRunService;
    private final AuthenticatedRequesterResolver requesterResolver;
    private final GuestAccessPolicy guestAccessPolicy;

    public LaroUserCommandController(
            SimulationCommandCycleService commandCycleService,
            SimulationRunService simulationRunService,
            AuthenticatedRequesterResolver requesterResolver,
            GuestAccessPolicy guestAccessPolicy
    ) {
        this.commandCycleService = commandCycleService;
        this.simulationRunService = simulationRunService;
        this.requesterResolver = requesterResolver;
        this.guestAccessPolicy = guestAccessPolicy;
    }

    @Operation(summary = "사용자 명령으로 기존 명령 사이클 즉시 실행")
    @PostMapping("/{simulationRunId}/user-command")
    public ResponseEntity<SimulationCommandCycleStatusResponse> submit(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody LaroUserCommandRequest request,
            Authentication authentication
    ) {
        AuthenticatedRequester requester = requesterResolver.resolve(authentication);
        guestAccessPolicy.requireUser(requester);
        simulationRunService.validateOwnership(simulationRunId, requester);
        return ResponseEntity.accepted().body(
                commandCycleService.triggerUserCommand(
                        simulationRunId,
                        request.executionVersion(),
                        request.userCommand()
                )
        );
    }
}
