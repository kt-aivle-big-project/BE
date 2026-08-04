package com.aivle.be.simulationrun.controller.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SimulationRunCreateRequest(

        @NotNull
        @Schema(description = "시뮬레이션을 실행할 창고 ID", example = "1")
        Long warehouseId,

        @Schema(description = "실행 배속 (0.5 / 1 / 2 / 3). 미지정 시 1배속", example = "1")
        Double simulationSpeed
) {
}
