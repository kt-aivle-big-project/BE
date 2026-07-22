package com.aivle.be.simulationrun.service;

import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SimulationRunProgressService {

    private static final Set<TaskStatus> TERMINAL_TASK_STATUSES = Set.of(
            TaskStatus.DONE,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED
    );

    private final SimulationRunRepository simulationRunRepository;
    private final TaskRepository taskRepository;
    private final SimulationRunStateStore simulationRunStateStore;

    @Transactional
    public void evaluateAfterTaskFinished(Long simulationRunId) {
        if (simulationRunId == null) {
            return;
        }
        SimulationRun run = simulationRunRepository.findById(simulationRunId).orElse(null);
        if (run == null || run.getStatus() != SimulationRunStatus.RUNNING) {
            return;
        }
        if (taskRepository.countBySimulationRun_Id(simulationRunId) == 0
                || taskRepository.existsBySimulationRun_IdAndStatusNotIn(
                simulationRunId,
                TERMINAL_TASK_STATUSES
        )) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (taskRepository.existsBySimulationRun_IdAndStatus(
                simulationRunId,
                TaskStatus.FAILED
        )) {
            run.fail(now);
        } else {
            run.complete(now);
        }
        simulationRunStateStore.deleteAll(simulationRunId);
    }
}
