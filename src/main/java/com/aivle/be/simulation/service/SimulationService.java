package com.aivle.be.simulation.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulation.controller.request.SimulationAgentInteractionRequest;
import com.aivle.be.simulation.controller.request.SimulationCompleteRequest;
import com.aivle.be.simulation.controller.request.SimulationCreateRequest;
import com.aivle.be.simulation.controller.request.SimulationPathUpdateRequest;
import com.aivle.be.simulation.controller.request.SimulationPolicyResultRequest;
import com.aivle.be.simulation.controller.response.PathOverlapResponse;
import com.aivle.be.simulation.controller.response.SimulationResponse;
import com.aivle.be.simulation.entity.Simulation;
import com.aivle.be.simulation.repository.SimulationRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulationService {

    private static final String TOPIC = "/topic/simulations";

    private final SimulationRepository simulationRepository;
    private final WarehouseRepository warehouseRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public SimulationResponse createSimulation(SimulationCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        Simulation simulation = new Simulation(
                warehouse,
                request.missionId(),
                request.robotId(),
                request.startNode(),
                request.endNode(),
                request.taskCode()
        );

        if (request.pathNodes() != null) {
            simulation.updatePath(request.pathNodes());
        }

        Simulation saved = simulationRepository.save(simulation);
        return broadcast(saved);
    }

    public SimulationResponse getSimulation(Long simulationId) {
        return new SimulationResponse(findSimulationOrThrow(simulationId));
    }

    public List<SimulationResponse> getAllSimulations() {
        return simulationRepository.findAll().stream()
                .map(SimulationResponse::new)
                .toList();
    }

    @Transactional
    public SimulationResponse recordAgentInteraction(Long simulationId, SimulationAgentInteractionRequest request) {
        Simulation simulation = findSimulationOrThrow(simulationId);
        simulation.recordAgentInteraction(
                request.agentInput(),
                request.agentOutput(),
                request.tokens(),
                request.latency(),
                request.toolCallId()
        );
        return new SimulationResponse(simulation);
    }

    @Transactional
    public SimulationResponse recordPolicyResult(Long simulationId, SimulationPolicyResultRequest request) {
        Simulation simulation = findSimulationOrThrow(simulationId);
        simulation.recordPolicyResult(request.ruleCode(), request.policyResult());
        return new SimulationResponse(simulation);
    }

    @Transactional
    public SimulationResponse updatePath(Long simulationId, SimulationPathUpdateRequest request) {
        Simulation simulation = findSimulationOrThrow(simulationId);
        simulation.updatePath(request.pathNodes());
        return broadcast(simulation);
    }

    public PathOverlapResponse checkPathOverlap(Long nodeId) {
        List<Simulation> affected = simulationRepository.findRunningSimulationsContainingNode(nodeId);
        List<Long> affectedIds = affected.stream().map(Simulation::getId).toList();
        return new PathOverlapResponse(nodeId, !affectedIds.isEmpty(), affectedIds);
    }

    @Transactional
    public SimulationResponse completeSimulation(Long simulationId, SimulationCompleteRequest request) {
        Simulation simulation = findSimulationOrThrow(simulationId);
        simulation.complete(request.success());
        return broadcast(simulation);
    }

    private Simulation findSimulationOrThrow(Long simulationId) {
        return simulationRepository.findById(simulationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_NOT_FOUND));
    }

    private SimulationResponse broadcast(Simulation simulation) {
        SimulationResponse response = new SimulationResponse(simulation);
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }
}
