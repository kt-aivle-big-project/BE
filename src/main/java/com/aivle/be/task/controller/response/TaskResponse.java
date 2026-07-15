package com.aivle.be.task.controller.response;

import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;

import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        TaskType taskType,
        TaskStatus status,
        Long robotId,
        LocalDateTime requestedAt,
        LocalDateTime assignedAt
) {
    // Entity -> Response 변환용 보조 생성자
    public TaskResponse(Task task) {
        this(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                task.getRobot() != null ? task.getRobot().getId() : null,
                task.getRequestedAt(),
                task.getAssignedAt()
        );
    }
}