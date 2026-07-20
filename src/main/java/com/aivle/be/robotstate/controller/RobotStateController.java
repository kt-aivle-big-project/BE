package com.aivle.be.robotstate.controller;

import com.aivle.be.robotstate.dto.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.dto.response.RobotStateResponse;
import com.aivle.be.robotstate.service.RobotStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Robot State", description = "로봇 현재 위치·배터리·상태 관리 API")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RobotStateController {

    private final RobotStateService robotStateService;

    @Operation(summary = "로봇 상태 갱신")
    @ApiResponse(responseCode = "200", description = "상태 갱신 성공")
    @ApiResponse(responseCode = "400", description = "위치 또는 입력값 오류")
    @ApiResponse(responseCode = "404", description = "로봇·노드·작업을 찾을 수 없음")
    @ApiResponse(responseCode = "409", description = "상태 전이·작업·이벤트 시각 충돌")
    @PutMapping("/robots/{robotId}/state")
    public ResponseEntity<RobotStateResponse> updateState(
            @PathVariable Long robotId,
            @Valid @RequestBody RobotStateUpdateRequest request
    ) {
        return ResponseEntity.ok(robotStateService.updateState(robotId, request));
    }

    @Operation(summary = "로봇 현재 상태 조회")
    @GetMapping("/robots/{robotId}/state")
    public ResponseEntity<RobotStateResponse> getState(@PathVariable Long robotId) {
        return ResponseEntity.ok(robotStateService.getState(robotId));
    }

    @Operation(summary = "창고별 로봇 현재 상태 목록 조회")
    @GetMapping("/warehouses/{warehouseId}/robots/states")
    public ResponseEntity<List<RobotStateResponse>> getWarehouseStates(
            @PathVariable Long warehouseId
    ) {
        return ResponseEntity.ok(robotStateService.getWarehouseStates(warehouseId));
    }
}
