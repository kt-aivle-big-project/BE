package com.aivle.be.task.controller;

import com.aivle.be.task.controller.request.TaskAssignRequest;
import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Task", description = "작업(Task) 생성·할당·진행 상태 관리 API")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @Operation(summary = "작업 생성")
    @ApiResponse(responseCode = "200", description = "작업 생성 성공")
    @ApiResponse(responseCode = "404", description = "창고·노드·창고품목을 찾을 수 없음")
    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@RequestBody TaskCreateRequest request) {
        return ResponseEntity.ok(taskService.createTask(request));
    }

    @Operation(summary = "작업 단건 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음")
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.getTask(taskId));
    }

    @Operation(summary = "작업 전체 목록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    public ResponseEntity<List<TaskResponse>> getAllTasks() {
        return ResponseEntity.ok(taskService.getAllTasks());
    }

    @Operation(summary = "작업에 로봇 할당")
    @ApiResponse(responseCode = "200", description = "할당 성공")
    @ApiResponse(responseCode = "404", description = "작업·로봇을 찾을 수 없음")
    @ApiResponse(responseCode = "409", description = "로봇이 이미 다른 작업에 할당되어 있거나, 작업이 이미 처리 중/종료됨")
    @PatchMapping("/{taskId}/assign")
    public ResponseEntity<TaskResponse> assignRobot(
            @PathVariable Long taskId,
            @RequestBody TaskAssignRequest request
    ) {
        return ResponseEntity.ok(taskService.assignRobot(taskId, request));
    }

    @Operation(summary = "작업 시작")
    @ApiResponse(responseCode = "200", description = "시작 처리 성공")
    @ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음")
    @ApiResponse(responseCode = "409", description = "상태 전이 불가")
    @PatchMapping("/{taskId}/start")
    public ResponseEntity<TaskResponse> startTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.startTask(taskId));
    }

    @Operation(summary = "작업 완료 처리")
    @ApiResponse(responseCode = "200", description = "완료 처리 성공")
    @ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음")
    @ApiResponse(responseCode = "409", description = "상태 전이 불가")
    @PatchMapping("/{taskId}/complete")
    public ResponseEntity<TaskResponse> completeTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.completeTask(taskId));
    }

    @Operation(summary = "작업 실패 처리")
    @ApiResponse(responseCode = "200", description = "실패 처리 성공")
    @ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음")
    @ApiResponse(responseCode = "409", description = "상태 전이 불가")
    @PatchMapping("/{taskId}/fail")
    public ResponseEntity<TaskResponse> failTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.failTask(taskId));
    }

    @Operation(summary = "작업 취소 (물리 삭제 대신 취소 처리, 이력 보존)")
    @ApiResponse(responseCode = "204", description = "취소 성공")
    @ApiResponse(responseCode = "404", description = "작업을 찾을 수 없음")
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> cancelTask(@PathVariable Long taskId) {
        taskService.cancelTask(taskId);
        return ResponseEntity.noContent().build();
    }
}