package com.aivle.be.scenario.controller.request;

import com.aivle.be.scenario.domain.ScenarioStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 시나리오 부분 수정 요청.
 *
 * <p>보낸 값만 바뀐다. 안 보낸 항목(null)은 그대로 둔다.
 * 시나리오 수정 패널과 목록의 상태 변경이 모두 이 요청을 쓴다.
 */
public record ScenarioUpdateRequest(
        String scenarioName,
        @Size(max = 500) String description,
        @Min(0) @Max(100) Integer initialBattery,
        @Min(0) @Max(100) Integer chargingThreshold,
        @Min(1) @Max(100) Integer robotCount,
        Double simulationSpeed,
        Boolean autoReplan,
        Boolean obstacleEnabled,
        ScenarioStatus status
) {
}
