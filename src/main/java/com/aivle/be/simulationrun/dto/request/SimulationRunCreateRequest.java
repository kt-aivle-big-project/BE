package com.aivle.be.simulationrun.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SimulationRunCreateRequest(
        @NotNull
        @Schema(description = "시뮬레이션을 실행할 창고 ID", example = "1")
        Long warehouseId
) {
}
