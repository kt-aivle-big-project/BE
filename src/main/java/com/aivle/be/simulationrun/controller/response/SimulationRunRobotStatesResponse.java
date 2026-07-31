package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;

import java.util.List;

/**
 * 실행 중인 시뮬레이션의 현재 스냅샷.
 *
 * 화면을 벗어났다가 돌아왔을 때 이 값으로 현재 상태를 그대로 복구한다.
 * (재생은 백엔드에서 계속 진행되므로 화면만 따라잡으면 된다)
 */
public record SimulationRunRobotStatesResponse(
        Long simulationRunId,
        SimulationRunStatus status,
        List<RobotStateResponse> robots,

        // 시뮬레이션 내부 경과 시간(ms). 진행 중이 아니면 0.
        long elapsedMillis
) {
}
