package com.aivle.be.simulationrun.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record SimulationRunCreateRequest(

        @NotNull
        @Schema(description = "시뮬레이션을 실행할 창고 ID", example = "1")
        Long warehouseId,

        @Schema(description = "사용할 시나리오 ID (지정 시 로봇 수·배터리 등 설정을 시나리오에서 가져옴)", example = "1")
        Long scenarioId,

        @Schema(description = "실행 배속 (0.5 / 1 / 2 / 3). 미지정 시 시나리오 값 사용", example = "1")
        Double simulationSpeed,

        @Valid
        @Schema(description = "입고 설정")
        InboundConfigRequest inbound,

        @Valid
        @Schema(description = "출고 설정")
        OutboundConfigRequest outbound,

        @Valid
        @Schema(description = "랜덤 시나리오 설정 (기존 방식)")
        ScenarioConfigRequest scenario
) {
}
