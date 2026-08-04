package com.aivle.be.optimization.validation;

import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;

import java.util.List;
import java.util.Set;

public record ReoptimizationPlanValidationInput(
        ReplanningSnapshot replanningSnapshot,
        ReoptimizationOptimizationRequest request,
        ReoptimizationResponse response,
        Set<Long> validNodeIds,
        List<DirectedEdge> directedEdges,
        Set<Long> warehouseEdgeIds,
        Set<Long> participantRobotIds,
        Set<Long> unavailableRobotIds
) {

    public ReoptimizationPlanValidationInput {
        validNodeIds = Set.copyOf(validNodeIds);
        directedEdges = List.copyOf(directedEdges);
        warehouseEdgeIds = Set.copyOf(warehouseEdgeIds);
        participantRobotIds = Set.copyOf(participantRobotIds);
        unavailableRobotIds = Set.copyOf(unavailableRobotIds);
    }

    public record DirectedEdge(
            Long edgeId,
            Long fromNodeId,
            Long toNodeId
    ) {
    }
}
