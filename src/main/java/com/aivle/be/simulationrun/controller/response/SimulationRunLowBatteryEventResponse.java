package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;

/** 포트폴리오 시뮬레이션에서 주입한 배터리 부족 이벤트 결과. */
public record SimulationRunLowBatteryEventResponse(
        Long simulationRunId,
        Long robotId,
        int previousBatteryLevel,
        int batteryLevel,
        int chargingThreshold,
        Long currentTaskId,
        RobotStatus robotStatus,
        long simulationClockMillis
) {
    public static SimulationRunLowBatteryEventResponse from(
            SimulationPlaybackService.LowBatteryInjection value
    ) {
        return new SimulationRunLowBatteryEventResponse(
                value.simulationRunId(),
                value.robotId(),
                value.previousBatteryLevel(),
                value.batteryLevel(),
                value.chargingThreshold(),
                value.currentTaskId(),
                value.robotStatus(),
                value.simulationClockMillis()
        );
    }
}
