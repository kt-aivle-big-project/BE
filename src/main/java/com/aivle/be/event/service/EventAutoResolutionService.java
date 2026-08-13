package com.aivle.be.event.service;

import com.aivle.be.event.entity.Event;
import com.aivle.be.event.entity.EventType;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleStatusResponse.CycleState;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class EventAutoResolutionService {

    private static final Logger log = LoggerFactory.getLogger(EventAutoResolutionService.class);
    private static final List<EventType> AUTO_TYPES = List.of(
            EventType.LOW_BATTERY,
            EventType.TASK_FAILED,
            EventType.COLLISION_RISK
    );

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final SimulationRunStateStore stateStore;
    private final SimulationPlaybackService playbackService;
    private final SimulationCommandCycleService commandCycleService;

    @Scheduled(fixedDelayString = "${simulation.events.auto-resolution-ms:1000}")
    @Transactional(readOnly = true)
    public void resolveCompletedEvents() {
        for (Event event : eventRepository
                .findAllByResolvedAtIsNullAndEventTypeIn(AUTO_TYPES)) {
            try {
                if (canResolve(event)) {
                    eventService.resolveAutomatically(event.getId());
                }
            } catch (RuntimeException exception) {
                log.debug("Event auto-resolution deferred: eventId={}, reason={}",
                        event.getId(), exception.getMessage());
            }
        }
    }

    private boolean canResolve(Event event) {
        SimulationRun run = event.getTask() == null
                ? null
                : event.getTask().getSimulationRun();
        if (run == null) {
            return false;
        }
        return switch (event.getEventType()) {
            case LOW_BATTERY -> recoveredBattery(event, run);
            case COLLISION_RISK -> !playbackService.isNodeOnRemainingAiRoute(
                    run.getId(), event.getNodeId());
            case TASK_FAILED -> replacementPlanReady(event, run);
            default -> false;
        };
    }

    private boolean recoveredBattery(Event event, SimulationRun run) {
        if (event.getRobot() == null) {
            return false;
        }
        RobotState state = stateStore
                .findByRobotId(run.getId(), event.getRobot().getId())
                .orElse(null);
        int threshold = run.getChargingThreshold() == null
                ? 20
                : run.getChargingThreshold();
        return state != null
                && state.status() == RobotStatus.CHARGING
                && state.batteryLevel() != null
                && state.batteryLevel() >= threshold;
    }

    private boolean replacementPlanReady(Event event, SimulationRun run) {
        var status = commandCycleService.status(run.getId());
        return status.state() == CycleState.COMPLETE
                && "REPLAN".equalsIgnoreCase(status.planningMode())
                && status.updatedAt() != null
                && status.updatedAt().isAfter(
                        event.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant());
    }
}
