package com.aivle.be.optimization.service;

import com.aivle.be.optimization.client.LaroPlanningClient;
import com.aivle.be.optimization.dto.request.LaroNativePlanRequest;
import com.aivle.be.optimization.dto.request.LaroHitlResponseRequest;
import com.aivle.be.optimization.dto.request.LaroPlanRequest;
import com.aivle.be.optimization.dto.response.LaroHitlResponse;
import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LaroPlanningService {

    private final LaroPlanningClient laroPlanningClient;
    private final SimulationPlaybackService simulationPlaybackService;

    public LaroPlanResponse createPlan(
            String warehouseId,
            LaroPlanRequest request
    ) {
        LaroNativePlanRequest nativeRequest =
                LaroNativePlanRequest.from(warehouseId, request);

        return laroPlanningClient.createPlan(
                warehouseId,
                nativeRequest
        );
    }

    public LaroPlanResponse createAndInstallPlan(
            Long simulationRunId,
            String warehouseId,
            LaroPlanRequest request
    ) {
        LaroPlanResponse response = laroPlanningClient.createPlan(
                warehouseId,
                LaroNativePlanRequest.from(
                        warehouseId,
                        simulationRunId,
                        request
                )
        );
        if (response != null && response.isValidated()) {
            simulationPlaybackService.installLaroPlan(
                    simulationRunId,
                    response
            );
        }
        return response;
    }

    public LaroHitlResponse respondAndInstallPlan(
            Long simulationRunId,
            String interactionId,
            LaroHitlResponseRequest request
    ) {
        LaroHitlResponse response = laroPlanningClient
                .respondToHumanInteraction(interactionId, request);
        LaroPlanResponse resumedPlan = response == null
                ? null
                : response.resumedPlan();
        if (resumedPlan != null && resumedPlan.isValidated()) {
            Long resumedRunId = response.orchestrationResult().simulationRunId();
            if (resumedRunId != null && !simulationRunId.equals(resumedRunId)) {
                throw new IllegalArgumentException(
                        "HITL result belongs to a different simulation run: " + resumedRunId
                );
            }
            simulationPlaybackService.installLaroPlan(simulationRunId, resumedPlan);
        }
        return response;
    }
}
