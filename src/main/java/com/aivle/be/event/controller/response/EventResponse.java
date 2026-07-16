package com.aivle.be.event.controller.response;

import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;

import java.time.LocalDateTime;

public record EventResponse(

        Long id,
        Long warehouseId,
        Long robotId,
        Long taskId,
        EventType eventType,
        String description,
        LocalDateTime occurredAt,
        LocalDateTime resolvedAt
) {
    public EventResponse(Event event) {
        this(

                event.getId(),
                event.getWarehouse().getId(),
                event.getRobot() != null ? event.getRobot().getId() : null,
                event.getTask() != null ? event.getTask().getId() : null,
                event.getEventType(),
                event.getDescription(),
                event.getOccurredAt(),
                event.getResolvedAt()
        );
    }
}