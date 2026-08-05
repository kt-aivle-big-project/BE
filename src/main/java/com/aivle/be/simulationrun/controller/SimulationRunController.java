package com.aivle.be.simulationrun.controller;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunHistoryResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Simulation Run", description = "전체 시뮬레이션 실행 관리 API")
@RestController
@RequestMapping("/api/simulation-runs")
@RequiredArgsConstructor
public class SimulationRunController {

    private final SimulationRunService simulationRunService;
    private final TaskService taskService;
    private final AuthenticatedRequesterResolver requesterResolver;

    @Operation(summary = "시뮬레이션 실행 생성")
    @PostMapping
    public ResponseEntity<SimulationRunResponse> create(
            @Valid @RequestBody SimulationRunCreateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(simulationRunService.create(request, requester(authentication)));
    }

    @Operation(summary = "시뮬레이션 시작")
    @PostMapping("/{simulationRunId}/start")
    public ResponseEntity<SimulationRunResponse> start(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.start(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "시뮬레이션 일시정지")
    @PostMapping("/{simulationRunId}/pause")
    public ResponseEntity<SimulationRunResponse> pause(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.pause(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "시뮬레이션 재개")
    @PostMapping("/{simulationRunId}/resume")
    public ResponseEntity<SimulationRunResponse> resume(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.resume(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "내가 실행했던 시뮬레이션 이력 조회")
    @GetMapping("/my")
    public ResponseEntity<List<SimulationRunHistoryResponse>> getMyRuns(
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.getMyRuns(requester(authentication))
        );
    }

    @Operation(summary = "창고에서 진행 중인 시뮬레이션 전체 중지")
    @PostMapping("/stop-active")
    public ResponseEntity<Void> stopActiveRuns(
            @RequestParam Long warehouseId,
            Authentication authentication
    ) {
        simulationRunService.stopActiveRuns(
                warehouseId,
                requester(authentication)
        );
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "시뮬레이션 실행 배속 변경 (진행 중에도 즉시 반영)")
    @PatchMapping("/{simulationRunId}/speed")
    public ResponseEntity<SimulationRunResponse> changeSpeed(
            @PathVariable Long simulationRunId,
            @Valid @RequestBody SimulationSpeedUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.changeSpeed(
                        simulationRunId,
                        request,
                        requester(authentication)
                )
        );
    }

    @Operation(summary = "시뮬레이션 초기화 (로봇 실시간 상태 삭제 후 대기 상태로 되돌림)")
    @PostMapping("/{simulationRunId}/reset")
    public ResponseEntity<SimulationRunResponse> reset(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.reset(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "시뮬레이션 수동 종료")
    @PostMapping("/{simulationRunId}/stop")
    public ResponseEntity<SimulationRunResponse> stop(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.stop(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "시뮬레이션 정상 완료")
    @PostMapping("/{simulationRunId}/complete")
    public ResponseEntity<SimulationRunResponse> complete(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationRunService.complete(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 실패 종료")
    @PostMapping("/{simulationRunId}/fail")
    public ResponseEntity<SimulationRunResponse> fail(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationRunService.fail(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 실행 상태 조회")
    @GetMapping("/{simulationRunId}/status")
    public ResponseEntity<SimulationRunResponse> getStatus(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.getStatus(simulationRunId, requester(authentication))
        );
    }

    @Operation(summary = "시뮬레이션 참여 로봇 조회")
    @GetMapping("/{simulationRunId}/robots")
    public ResponseEntity<SimulationRunParticipantsResponse> getParticipants(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.getParticipants(
                        simulationRunId,
                        requester(authentication)
                )
        );
    }

    @Operation(summary = "시뮬레이션 실행 작업 목록 조회")
    @GetMapping("/{simulationRunId}/tasks")
    public ResponseEntity<List<TaskResponse>> getTasks(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        simulationRunService.getStatus(simulationRunId, requester(authentication));
        return ResponseEntity.ok(taskService.getTasksBySimulationRun(simulationRunId));
    }

    @Operation(summary = "시뮬레이션 로봇 실시간 상태 조회")
    @GetMapping("/{simulationRunId}/robots/states")
    public ResponseEntity<SimulationRunRobotStatesResponse> getRobotStates(
            @PathVariable Long simulationRunId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.getRobotStates(
                        simulationRunId,
                        requester(authentication)
                )
        );
    }

    @Operation(summary = "시뮬레이션 참여 로봇 실시간 상태 갱신")
    @PatchMapping("/{simulationRunId}/robots/{robotId}/state")
    public ResponseEntity<RobotStateResponse> updateRobotState(
            @PathVariable Long simulationRunId,
            @PathVariable Long robotId,
            @Valid @RequestBody RobotStateUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                simulationRunService.updateRobotState(simulationRunId, robotId, request)
        );
    }

    private AuthenticatedRequester requester(Authentication authentication) {
        return requesterResolver.resolve(authentication);
    }
}
