package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;

import java.util.List;

public record SimulationRunRobotStatesResponse(
        Long simulationRunId,
        long executionVersion,
        SimulationRunStatus status,
        List<RobotStateResponse> robots,

        long elapsedMillis
) {
}
