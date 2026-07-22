package com.aivle.be.simulationrun.controller;

import com.aivle.be.simulationrun.dto.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.dto.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.dto.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.dto.response.SimulationRunResponse;
import com.aivle.be.simulationrun.service.SimulationRunService;
import com.aivle.be.robotstate.dto.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.dto.response.RobotStateResponse;
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
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Simulation Run", description = "전체 시뮬레이션 실행 관리 API")
@RestController
@RequestMapping("/api/simulation-runs")
@RequiredArgsConstructor
public class SimulationRunController {

    private final SimulationRunService simulationRunService;

    @Operation(summary = "시뮬레이션 실행 생성")
    @PostMapping
    public ResponseEntity<SimulationRunResponse> create(
            @Valid @RequestBody SimulationRunCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(simulationRunService.create(request));
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
