package com.aivle.be.scenario.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 시나리오 생성 요청.
 *
 * <p>화면(시나리오 생성 패널)은 이름·설명·배터리 두 값만 받는다.
 * 나머지는 실행할 때 조정하는 값이라 필수로 두지 않고,
 * 안 보내면 서버가 기본값으로 채운다.
 */
public record ScenarioRequest(
        @NotBlank String scenarioName,

        // 안 보내면 S1, S2 ... 로 자동 부여한다.
        @Size(max = 50) String scenarioCode,

        @Size(max = 500) String description,

        // 기본값 : 배터리 100%, 충전 기준 20%
        @Min(0) @Max(100) Integer initialBattery,
        @Min(0) @Max(100) Integer chargingThreshold,

        // robotCount를 안 보내면 기본값 1을 사용한다.
        // 나머지 기본값: 1배속, 자동 재계획 켜짐, 장애물 꺼짐
        @Min(1) @Max(100) Integer robotCount,
        Double simulationSpeed,
        Boolean autoReplan,
        Boolean obstacleEnabled
) {
}
