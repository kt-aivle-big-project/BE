package com.aivle.be.simulationrun.controller;

import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationLaunchRequest;
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunRobotStatesResponse;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulationrun.controller.response.SimulationRunHistoryResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.controller.response.SimulationLaunchResponse;
import com.aivle.be.simulationrun.service.SimulationLaunchService;
import com.aivle.be.simulationrun.service.SimulationRunService;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.robotstate.controller.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Simulation Run", description = "전체 시뮬레이션 실행 관리 API")
@RestController
@RequestMapping("/api/simulation-runs")
@RequiredArgsConstructor
public class SimulationRunController {

    private final SimulationRunService simulationRunService;
    private final SimulationLaunchService simulationLaunchService;
    private final TaskService taskService;

    @Operation(summary = "시뮬레이션 생성, 시작, AI 계획 설치")
    @PostMapping("/launch")
    public ResponseEntity<SimulationLaunchResponse> launch(
            @Valid @RequestBody SimulationLaunchRequest request,
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(simulationLaunchService.launch(request, parseUserId(userId)));
    }

    @Operation(summary = "시뮬레이션 실행 생성")
    @PostMapping
    public ResponseEntity<SimulationRunResponse> create(
            @Valid @RequestBody SimulationRunCreateRequest request,
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(simulationRunService.create(request, parseUserId(userId)));
    }

    @Operation(summary = "시뮬레이션 시작")
    @PostMapping("/{simulationRunId}/start")
    public ResponseEntity<SimulationRunResponse> start(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.start(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 일시정지")
    @PostMapping("/{simulationRunId}/pause")
    public ResponseEntity<SimulationRunResponse> pause(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.pause(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 재개")
    @PostMapping("/{simulationRunId}/resume")
    public ResponseEntity<SimulationRunResponse> resume(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.resume(simulationRunId));
    }

    @Operation(summary = "내가 실행했던 시뮬레이션 이력 조회")
    @GetMapping("/my")
    public ResponseEntity<List<SimulationRunHistoryResponse>> getMyRuns(
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.ok(
                simulationRunService.getMyRuns(parseUserId(userId))
        );
    }

    /**
     * 인증 정보에서 사용자 ID를 꺼낸다.
     *
     * JwtAuthenticationFilter 가 토큰의 subject(사용자 ID 문자열)를 principal 로 심는다.
     * 추후 필터가 사용자 객체를 심도록 바뀌면 이 메서드만 고치면 된다.
     */
    private Long parseUserId(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        try {
            return Long.valueOf(principal);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
    }

    @Operation(summary = "창고에서 진행 중인 시뮬레이션 전체 중지")
    @PostMapping("/stop-active")
    public ResponseEntity<Void> stopActiveRuns(@RequestParam Long warehouseId) {
        simulationRunService.stopActiveRuns(warehouseId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "시뮬레이션 실행 배속 변경 (진행 중에도 즉시 반영)")
    @PatchMapping("/{simulationRunId}/speed")
    public ResponseEntity<SimulationRunResponse> changeSpeed(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody SimulationSpeedUpdateRequest request
    ) {
        return ResponseEntity.ok(
                simulationRunService.changeSpeed(simulationRunId, request)
        );
    }

    @Operation(summary = "시뮬레이션 초기화 (로봇 실시간 상태 삭제 후 대기 상태로 되돌림)")
    @PostMapping("/{simulationRunId}/reset")
    public ResponseEntity<SimulationRunResponse> reset(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.reset(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 수동 종료")
    @PostMapping("/{simulationRunId}/stop")
    public ResponseEntity<SimulationRunResponse> stop(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.stop(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 정상 완료")
    @PostMapping("/{simulationRunId}/complete")
    public ResponseEntity<SimulationRunResponse> complete(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.complete(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 실패 종료")
    @PostMapping("/{simulationRunId}/fail")
    public ResponseEntity<SimulationRunResponse> fail(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.fail(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 실행 상태 조회")
    @GetMapping("/{simulationRunId}/status")
    public ResponseEntity<SimulationRunResponse> getStatus(@PathVariable Long simulationRunId) {
        return ResponseEntity.ok(simulationRunService.getStatus(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 참여 로봇 조회")
    @GetMapping("/{simulationRunId}/robots")
    public ResponseEntity<SimulationRunParticipantsResponse> getParticipants(
            @PathVariable Long simulationRunId
    ) {
        return ResponseEntity.ok(simulationRunService.getParticipants(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 실행 작업 목록 조회")
    @GetMapping("/{simulationRunId}/tasks")
    public ResponseEntity<List<TaskResponse>> getTasks(@PathVariable Long simulationRunId) {
        simulationRunService.getStatus(simulationRunId);
        return ResponseEntity.ok(taskService.getTasksBySimulationRun(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 로봇 실시간 상태 조회")
    @GetMapping("/{simulationRunId}/robots/states")
    public ResponseEntity<SimulationRunRobotStatesResponse> getRobotStates(
            @PathVariable Long simulationRunId
    ) {
        return ResponseEntity.ok(simulationRunService.getRobotStates(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 참여 로봇 실시간 상태 갱신")
    @PatchMapping("/{simulationRunId}/robots/{robotId}/state")
    public ResponseEntity<RobotStateResponse> updateRobotState(
            @PathVariable Long simulationRunId,
            @PathVariable Long robotId,
            @Valid @RequestBody RobotStateUpdateRequest request
    ) {
        return ResponseEntity.ok(
                simulationRunService.updateRobotState(simulationRunId, robotId, request)
        );
    }
}
