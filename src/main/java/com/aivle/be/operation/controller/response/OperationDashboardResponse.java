package com.aivle.be.operation.controller.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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

    public record HourlyCount(String hour, long count, long completedCount) {}

    public record StatusCount(String key, long count) {}

    public record WarehouseCount(
            Long warehouseId,
            String warehouseName,
            long count,
            long totalCount,
            int completionRate
    ) {}

    public record RecentTask(
            Long taskId,
            String taskCode,
            String warehouseName,
            String taskType,
            String status,
            String startedAt,
            String completedAt,
            Long delayMinutes
    ) {}
}
