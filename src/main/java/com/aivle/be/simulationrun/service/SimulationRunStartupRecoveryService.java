package com.aivle.be.simulationrun.service;

import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SimulationRunStartupRecoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunStartupRecoveryService.class);

    private static final Set<SimulationRunStatus> ORPHANABLE_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED,
            SimulationRunStatus.QUIESCING,
            SimulationRunStatus.REPLANNING,
            SimulationRunStatus.PENDING_ACTIVATION
    );

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final LaroInventoryReservationService inventoryReservationService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void stopOrphanedRuns() {
        List<SimulationRun> orphanedRuns =
                simulationRunRepository.findAllByStatusIn(ORPHANABLE_STATUSES);
        if (orphanedRuns.isEmpty()) {
            return;
        }

        LocalDateTime stoppedAt = LocalDateTime.now();
        for (SimulationRun run : orphanedRuns) {
            Long simulationRunId = run.getId();
            run.stop(stoppedAt);
            simulationRunStateStore.deleteAll(simulationRunId);
            inventoryReservationService.releaseActiveForRun(simulationRunId);

            for (Task task : taskRepository
                    .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId)) {
                if (task.getStatus() == TaskStatus.PENDING
                        || task.getStatus() == TaskStatus.ASSIGNED
                        || task.getStatus() == TaskStatus.IN_PROGRESS) {
                    task.cancel();
                }
            }
        }

        log.info(
                "Stopped {} orphaned simulation run(s) left by the previous BE process",
                orphanedRuns.size()
        );
    }
}
