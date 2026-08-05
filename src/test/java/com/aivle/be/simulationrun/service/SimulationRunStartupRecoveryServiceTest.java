package com.aivle.be.simulationrun.service;

import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationRunStartupRecoveryServiceTest {

    @Mock
    private SimulationRunRepository simulationRunRepository;

    @Mock
    private SimulationRunStateStore simulationRunStateStore;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private LaroInventoryReservationService inventoryReservationService;

    @Mock
    private Task task;

    @InjectMocks
    private SimulationRunStartupRecoveryService recoveryService;

    @Test
    void stopsOrphanedRunAndClearsEphemeralExecutionState() {
        SimulationRun run = SimulationRun.create(null, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 1L);
        run.start(LocalDateTime.now());

        when(simulationRunRepository.findAllByStatusIn(anyCollection()))
                .thenReturn(List.of(run));
        when(taskRepository.findAllBySimulationRun_IdOrderByRequestedAtAsc(run.getId()))
                .thenReturn(List.of(task));
        when(task.getStatus()).thenReturn(TaskStatus.ASSIGNED);

        recoveryService.stopOrphanedRuns();

        assertEquals(SimulationRunStatus.STOPPED, run.getStatus());
        verify(simulationRunStateStore).deleteAll(run.getId());
        verify(inventoryReservationService).releaseActiveForRun(run.getId());
        verify(task).cancel();
    }
}
