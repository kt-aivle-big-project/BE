package com.aivle.be.task.controller.request;

import com.aivle.be.task.entity.Task;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskCreateRequest {

    private Long warehouseId;
    private Long startNodeId;
    private Long endNodeId;
    private Long warehouseItemId; // 입출고 작업일 때만 채움, 없으면 null
    private Task.TaskType taskType;
}