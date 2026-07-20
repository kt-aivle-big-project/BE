package com.aivle.be.simulation.controller;

import com.aivle.be.simulation.controller.request.SimulationAgentInteractionRequest;
import com.aivle.be.simulation.controller.request.SimulationCompleteRequest;
import com.aivle.be.simulation.controller.request.SimulationCreateRequest;
import com.aivle.be.simulation.controller.request.SimulationPolicyResultRequest;
import com.aivle.be.simulation.controller.response.SimulationResponse;
import com.aivle.be.simulation.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;

    @PostMapping
    public ResponseEntity<SimulationResponse> createSimulation(@RequestBody SimulationCreateRequest request) {
        return ResponseEntity.ok(simulationService.createSimulation(request));
    }

    @GetMapping("/{simulationId}")
    public ResponseEntity<SimulationResponse> getSimulation(@PathVariable Long simulationId) {
        return ResponseEntity.ok(simulationService.getSimulation(simulationId));
    }

    @GetMapping
    public ResponseEntity<List<SimulationResponse>> getAllSimulations() {
        return ResponseEntity.ok(simulationService.getAllSimulations());
    }

    @PatchMapping("/{simulationId}/agent-interaction")
    public ResponseEntity<SimulationResponse> recordAgentInteraction(
            @PathVariable Long simulationId,
            @RequestBody SimulationAgentInteractionRequest request
    ) {
        return ResponseEntity.ok(simulationService.recordAgentInteraction(simulationId, request));
    }

    @PatchMapping("/{simulationId}/policy-result")
    public ResponseEntity<SimulationResponse> recordPolicyResult(
            @PathVariable Long simulationId,
            @RequestBody SimulationPolicyResultRequest request
    ) {
        return ResponseEntity.ok(simulationService.recordPolicyResult(simulationId, request));
    }

    @PatchMapping("/{simulationId}/complete")
    public ResponseEntity<SimulationResponse> completeSimulation(
            @PathVariable Long simulationId,
            @RequestBody SimulationCompleteRequest request
    ) {
        return ResponseEntity.ok(simulationService.completeSimulation(simulationId, request));
    }
}