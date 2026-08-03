package com.aivle.be.simulationrun.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.graph.service.AiRouteGraphSyncService;
import com.aivle.be.optimization.dto.request.LaroPlanRequest;
import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import com.aivle.be.optimization.service.AiPostgresContractSyncService;
import com.aivle.be.optimization.service.LaroPlanningService;
import com.aivle.be.simulationrun.controller.request.SimulationStartRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.controller.response.SimulationStartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SimulationStartService {

    private final SimulationRunService simulationRunService;
    private final LaroPlanningService laroPlanningService;
    private final AiPostgresContractSyncService aiPostgresContractSyncService;

    public SimulationStartResponse startAndInstall(
            Long simulationRunId,
            SimulationStartRequest request,
            AuthenticatedRequester requester
    ) {
        SimulationRunResponse started = simulationRunService.start(
                simulationRunId,
                requester
        );
        AiPostgresContractSyncService.ContractEvents contractEvents =
                aiPostgresContractSyncService.syncSimulationTasks(
                        simulationRunId,
                        started.warehouseId()
                );
        String aiWarehouseId =
                AiRouteGraphSyncService.toAiWarehouseId(started.warehouseId());

        String requestedBackend = request == null ? null : request.optimizationBackend();
        String requestedCommand = request == null ? null : request.userCommand();
        String backend = requestedBackend == null || requestedBackend.isBlank()
                ? "ortools"
                : requestedBackend;
        String command = requestedCommand == null || requestedCommand.isBlank()
                ? null
                : requestedCommand;

        List<LaroPlanRequest.EventInput> events = new ArrayList<>();
        contractEvents.orderIds().forEach(orderId -> events.add(
                new LaroPlanRequest.EventInput(
                        "new_order", orderId, null, null, null, null, Map.of()
                )
        ));
        contractEvents.inboundIds().forEach(inboundId -> events.add(
                new LaroPlanRequest.EventInput(
                        "inbound_item_arrived", null, inboundId, null, null, null, Map.of()
                )
        ));

        LaroPlanResponse plan = laroPlanningService.createAndInstallPlan(
                simulationRunId,
                aiWarehouseId,
                new LaroPlanRequest(
                        "SIM-RUN-" + simulationRunId,
                        backend,
                        List.copyOf(events),
                        command
                )
        );

        return new SimulationStartResponse(aiWarehouseId, started, plan);
    }
}
