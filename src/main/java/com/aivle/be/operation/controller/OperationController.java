package com.aivle.be.operation.controller;

import com.aivle.be.operation.controller.response.OperationDashboardResponse;
import com.aivle.be.operation.service.OperationDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Operation", description = "운영 관리 API")
@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationDashboardService operationDashboardService;

    /**
     * 운영 관리 화면의 모든 지표를 한 번에 내려준다.
     *
     * <p>작업·이벤트·로봇을 따로 받아 화면에서 더하면
     * 작업이 쌓일수록 전부 내려받아야 해서 느려진다. 집계는 서버에서 한다.
     */
    @Operation(
            summary = "운영 대시보드 조회",
            description = "요약 카드, 시간대별 작업량·이벤트, 로봇 상태 분포, "
                    + "창고별 처리량, 최근 작업을 한 번에 돌려준다."
    )
    @GetMapping("/dashboard")
    public ResponseEntity<OperationDashboardResponse> getDashboard(
            @RequestParam(required = false) Long warehouseId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(
                operationDashboardService.getDashboard(warehouseId, startDate, endDate)
        );
    }

    /**
     * 대시보드와 같은 조건의 작업을 자르지 않고 전부 내려준다.
     *
     * <p>대시보드 응답은 화면이 무거워지지 않게 최근 10건만 담는다.
     * 「전체 보기」 팝업을 열 때만 이 API 를 부른다.
     */
    @Operation(
            summary = "운영 작업 전체 조회",
            description = "선택한 창고와 기간의 작업을 최신순으로 모두 돌려준다."
    )
    @GetMapping("/tasks")
    public ResponseEntity<List<OperationDashboardResponse.RecentTask>> getTasks(
            @RequestParam(required = false) Long warehouseId,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(
                operationDashboardService.getTasks(warehouseId, startDate, endDate)
        );
    }
}
