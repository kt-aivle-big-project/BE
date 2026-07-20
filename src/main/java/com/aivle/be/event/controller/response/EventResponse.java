package com.aivle.be.event.controller.response;

import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;

import java.time.LocalDateTime;
import java.util.List;

public record EventResponse(
        Long id,
        Long warehouseId,
        Long robotId,
        Long taskId,
        EventType eventType,
        String description,
        Long nodeId,
        LocalDateTime occurredAt,
        LocalDateTime resolvedAt,
        Boolean overlapping,
        List<Long> affectedSimulationIds
) {
    public EventResponse(Event event) {
        this(event, null, null);
    }

    public EventResponse(Event event, Boolean overlapping, List<Long> affectedSimulationIds) {
        this(
                event.getId(),
                event.getWarehouse().getId(),
                event.getRobot() != null ? event.getRobot().getId() : null,
                event.getTask() != null ? event.getTask().getId() : null,
                event.getEventType(),
                event.getDescription(),
                event.getNodeId(),
                event.getOccurredAt(),
                event.getResolvedAt(),
                overlapping,
                affectedSimulationIds
        );
    }
}