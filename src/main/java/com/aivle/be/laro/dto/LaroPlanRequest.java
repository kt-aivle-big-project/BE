package com.aivle.be.laro.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Spring BE가 LARO 계획 API에 전달할 공개 요청 DTO.
 *
 * <p>업무 사실은 이 요청의 {@code structuredInput}이 권위값이다. LARO는 별도
 * orders/handling_units 마스터 테이블을 조회하지 않는다. {@code userCommand}는
 * 우선순위·통로 정책·목적함수 같은 운영 의도를 보충하며 구조화 업무를 삭제하거나
 * 새 업무를 만들어 내는 근거가 될 수 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LaroPlanRequest(
        @NotNull @Valid StructuredInput structuredInput,
        @Size(max = 4000) String userCommand,
        String optimizationBackend,
        @Valid RuntimeSnapshot runtimeSnapshot
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructuredInput(
            @Size(max = 128) String requestId,
            @NotEmpty List<@Valid StructuredOperation> operations,
            Map<String, Object> constraints,
            @Valid RoutingContext routingContext
    ) {}

    /** Rule/Agent 분기 전에 사용하는 요청 시점의 작업 부하 스냅샷. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoutingContext(
            @PositiveOrZero Integer newOperationCount,
            @PositiveOrZero Integer unfinishedOperationCount,
            @PositiveOrZero Integer eligibleRobotCount,
            @PositiveOrZero Integer totalRobotCount,
            @PositiveOrZero Integer lowBatteryRobotCount,
            @PositiveOrZero Integer activeRobotCount,
            String source
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StructuredOperation(
            @NotBlank @Size(max = 128) String operationId,
            @NotNull OperationType operationType,
            @Positive Long taskId,
            @Positive Long itemId,
            @Size(max = 64) String productCode,
            @Positive Integer quantity,
            String priority,

            @Positive Long sourceWarehouseItemId,
            @Positive Long sourceStorageLocationId,
            @Positive Long sourceNodeId,
            @Size(max = 100) String sourceNodeCode,
            @Size(max = 100) String sourceFacilityCode,

            @Positive Long destinationStorageLocationId,
            @Positive Long destinationNodeId,
            @Size(max = 100) String destinationNodeCode,
            @Size(max = 100) String destinationFacilityCode,
            @Positive Integer targetRackLevel,

            @PositiveOrZero Long releaseAtMs,
            @PositiveOrZero Long pickupServiceTimeMs,
            @PositiveOrZero Long dropServiceTimeMs,
            @Size(max = 2000) String attributes
    ) {}

    public enum OperationType {
        OUTBOUND,
        INBOUND,
        TRANSFER,
        CHARGE,
        PARK
    }

    /** 테스트·재현용 선택 필드. 운영에서는 공유 Redis가 권위값이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RuntimeSnapshot(
            String mode,
            @PositiveOrZero Long capturedAtSimTimeMs,
            List<Map<String, Object>> robotStates,
            List<Map<String, Object>> preservedEdgeReservations,
            List<Map<String, Object>> preservedNodeReservations,
            List<Map<String, Object>> preservedStationReservations
    ) {}
}
