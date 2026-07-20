package com.aivle.be.simulation.controller.response;

import com.aivle.be.simulation.entity.Simulation;

import java.time.LocalDateTime;
import java.util.List;

public record SimulationResponse(
        Long id,
        Long warehouseId,
        Long missionId,
        Long robotId,
        Long startNode,
        Long endNode,
        String taskCode,
        List<Long> pathNodes,
        String agentInput,
        String agentOutput,
        Integer tokens,
        Long latency,
        String toolCallId,
        Boolean success,
        String ruleCode,
        String policyResult,
        LocalDateTime executedAt,
        LocalDateTime completedAt
) {
    public SimulationResponse(Simulation simulation) {
        this(
                simulation.getId(),
                simulation.getWarehouse().getId(),
                simulation.getMissionId(),
                simulation.getRobotId(),
                simulation.getStartNode(),
                simulation.getEndNode(),
                simulation.getTaskCode(),
                simulation.getPathNodes(),
                simulation.getAgentInput(),
                simulation.getAgentOutput(),
                simulation.getTokens(),
                simulation.getLatency(),
                simulation.getToolCallId(),
                simulation.getSuccess(),
                simulation.getRuleCode(),
                simulation.getPolicyResult(),
                simulation.getExecutedAt(),
                simulation.getCompletedAt()
        );
    }
}