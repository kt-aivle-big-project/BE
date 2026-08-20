package com.aivle.be.event.controller.request;

import com.aivle.be.event.entity.EventType;

public record EventCreateRequest(
        Long warehouseId,
        Long robotId,
        Long taskId,
        EventType eventType,
        String description,
        Long nodeId
) {
}