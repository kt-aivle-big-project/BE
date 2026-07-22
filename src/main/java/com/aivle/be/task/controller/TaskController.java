package com.aivle.be.task.controller;

import com.aivle.be.task.controller.request.TaskAssignRequest;
import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@RequestBody TaskCreateRequest request) {
        return ResponseEntity.ok(taskService.createTask(request));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.getTask(taskId));
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> getAllTasks() {
        return ResponseEntity.ok(taskService.getAllTasks());
    }

    @PatchMapping("/{taskId}/assign")
    public ResponseEntity<TaskResponse> assignRobot(
            @PathVariable Long taskId,
            @RequestBody TaskAssignRequest request
    ) {
        return ResponseEntity.ok(taskService.assignRobot(taskId, request));
    }

    @PatchMapping("/{taskId}/start")
    public ResponseEntity<TaskResponse> startTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.startTask(taskId));
    }

    @PatchMapping("/{taskId}/complete")
    public ResponseEntity<TaskResponse> completeTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.completeTask(taskId));
    }

    @PatchMapping("/{taskId}/fail")
    public ResponseEntity<TaskResponse> failTask(@PathVariable Long taskId) {
        return ResponseEntity.ok(taskService.failTask(taskId));
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> cancelTask(@PathVariable Long taskId) {
        taskService.cancelTask(taskId);
        return ResponseEntity.noContent().build();
    }
}