package com.aivle.be.operation.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 운영 관리 화면이 한 번에 받아 가는 대시보드 데이터.
 *
 * <p>화면에서 쓰는 그대로의 모양으로 내려준다.
 * 프론트가 여러 API 를 받아 직접 집계하면 작업이 쌓일수록 느려지고,
 * 같은 계산이 화면마다 흩어지기 때문이다.
 */
public record OperationDashboardResponse(

        @Schema(description = "상단 요약 카드")
        Summary summary,

        @Schema(description = "시간대별 작업 발생 수 (2시간 단위 12칸)")
        List<HourlyCount> hourlyTaskVolume,

        @Schema(description = "시간대별 이벤트 발생 수 (2시간 단위 12칸)")
        List<HourlyCount> hourlyEventVolume,

        @Schema(description = "로봇 상태 분포")
        List<StatusCount> robotStatusDistribution,

        @Schema(description = "창고별 완료 작업 수")
        List<WarehouseCount> warehouseThroughput,

        @Schema(description = "최근 작업 목록")
        List<RecentTask> recentTasks,

        @Schema(description = "집계 기준 시각", example = "2026-08-03 10:30")
        String updatedAt
) {

    public record Summary(
            @Schema(description = "선택 기간에 발생한 작업 수") long todayTaskCount,
            @Schema(description = "완료 작업 비율(%)") int completionRate,
            @Schema(description = "지금 작업 중인 로봇 수") long activeRobotCount,
            @Schema(description = "충전 기준 이하 로봇 수") long chargingRequiredRobotCount,
            @Schema(description = "오류 상태 로봇 수") long errorRobotCount
    ) {}

    /** hour 는 화면 라벨과 같은 "00시" 형식이다. */
    public record HourlyCount(String hour, long count) {}

    /** key 는 화면의 상태 구분값(AVAILABLE, WORKING, ...)이다. */
    public record StatusCount(String key, long count) {}

    public record WarehouseCount(Long warehouseId, String warehouseName, long count) {}

    public record RecentTask(
            Long taskId,
            String taskCode,
            String warehouseName,
            String taskType,
            String status,
            String startedAt,
            String completedAt
    ) {}
}
