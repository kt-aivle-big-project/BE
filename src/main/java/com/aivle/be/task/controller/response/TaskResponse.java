package com.aivle.be.task.controller.response;

import com.aivle.be.task.entity.Task;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class TaskResponse {

    private Long id;
    private Task.TaskType taskType;
    private Task.TaskStatus status;
    private LocalDateTime requestedAt;

    public TaskResponse(Task task) {
        this.id = task.getId();
        this.taskType = task.getTaskType();
        this.status = task.getStatus();
        this.requestedAt = task.getRequestedAt();
    }
}