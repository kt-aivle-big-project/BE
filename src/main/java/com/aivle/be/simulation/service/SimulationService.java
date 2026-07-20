package com.aivle.be.simulation.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulation.controller.request.SimulationAgentInteractionRequest;
import com.aivle.be.simulation.controller.request.SimulationCompleteRequest;
import com.aivle.be.simulation.controller.request.SimulationCreateRequest;
import com.aivle.be.simulation.controller.request.SimulationPolicyResultRequest;
import com.aivle.be.simulation.controller.response.SimulationResponse;
import com.aivle.be.simulation.entity.Simulation;
import com.aivle.be.simulation.repository.SimulationRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulationService {

    private final SimulationRepository simulationRepository;
    private final WarehouseRepository warehouseRepository;

    // ===== Create (= 시작) =====

    @Transactional
    public SimulationResponse createSimulation(SimulationCreateRequest request) {
        Warehouse warehouse = warehouseRepository.getReferenceById(request.warehouseId());

        Simulation simulation = new Simulation(
                warehouse,
                request.missionId(),
                request.robotId(),
                request.startNode(),
                request.endNode(),
                request.taskCode()
        );

        Simulation saved = simulationRepository.save(simulation);
        return new SimulationResponse(saved);
    }

    // ===== Read =====

    public SimulationResponse getSimulation(Long simulationId) {
        return new SimulationResponse(findSimulationOrThrow(simulationId));
    }

    public List<SimulationResponse> getAllSimulations() {
        return simulationRepository.findAll().stream()
                .map(SimulationResponse::new)
                .toList();
    }

    // ===== Update (진행 중 기록) =====

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

    // ===== Update (종료 = 중지) =====

    @Transactional
    public SimulationResponse completeSimulation(Long simulationId, SimulationCompleteRequest request) {
        Simulation simulation = findSimulationOrThrow(simulationId);
        simulation.complete(request.success());
        return new SimulationResponse(simulation);
    }

    // ===== 공통 조회 헬퍼 =====

    private Simulation findSimulationOrThrow(Long simulationId) {
        return simulationRepository.findById(simulationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_NOT_FOUND));
    }
}