package com.aivle.be.simulationrun.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record SimulationStartRequest(
        @Schema(example = "ortools")
        String optimizationBackend,
        @Schema(
                description = "선택 자연어 명령. 없으면 구조화된 작업만으로 계획합니다.",
                nullable = true
        )
        String userCommand
) {
}
