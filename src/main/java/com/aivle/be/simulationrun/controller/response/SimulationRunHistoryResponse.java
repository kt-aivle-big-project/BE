package com.aivle.be.simulationrun.controller.response;

import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "시뮬레이션 실행 이력")
public record SimulationRunHistoryResponse(

        @Schema(description = "실행 ID", example = "7")
        Long simulationRunId,

        @Schema(description = "창고 ID", example = "1")
        Long warehouseId,

        @Schema(description = "사용한 시나리오 ID", example = "1")
        Long scenarioId,

        @Schema(description = "시나리오 이름", example = "시나리오 v1")
        String scenarioName,

        @Schema(description = "실행 상태", example = "COMPLETED")
        SimulationRunStatus status,

        @Schema(description = "실행 배속", example = "1.0")
        Double simulationSpeed,

        @Schema(description = "이 실행에 등록된 작업 수", example = "40")
        long taskCount,

        @Schema(
                description = "작업 생성에 사용한 입출고 설정(JSON 원문). "
                        + "같은 설정으로 다시 실행할 때 사용한다.",
                example = "{\"inbound\":{\"inboundCount\":10}}"
        )
        String generationConfig,

        @Schema(description = "생성 시각")
        LocalDateTime createdAt,

        @Schema(description = "시작 시각")
        LocalDateTime startedAt,

        @Schema(description = "종료 시각")
        LocalDateTime endedAt
) {

    public static SimulationRunHistoryResponse of(SimulationRun run, long taskCount) {
        return new SimulationRunHistoryResponse(
                run.getId(),
                run.getWarehouse().getId(),
                run.getScenario() == null ? null : run.getScenario().getId(),
                run.getScenario() == null ? null : run.getScenario().getScenarioName(),
                run.getStatus(),
                run.getSimulationSpeed(),
                taskCount,
                run.getGenerationConfig(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getEndedAt()
        );
    }
}
