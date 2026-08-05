package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandGenerationService;
import com.aivle.be.laro.service.LaroPlanService;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationCommandCycleServiceTest {

    @Mock
    private SimulationRunRepository simulationRunRepository;
    @Mock
    private FulfillmentCommandGenerationService commandGenerationService;
    @Mock
    private LaroPlanService laroPlanService;
    @Mock
    private SimulationPlaybackService playbackService;
    @Mock
    private TaskExecutor taskExecutor;
    @Mock
    private SimulationRun run;

    @InjectMocks
    private SimulationCommandCycleService service;

    @Test
    void stopRemovesOldClockAndReturnsZeroedIdleStatus() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getStatus()).thenReturn(SimulationRunStatus.CREATED);

        service.configure(1L, FulfillmentCommandGenerateRequest.automatic());
        service.stop(1L);

        SimulationCommandCycleStatusResponse status = service.status(1L);
        assertFalse(status.active());
        assertEquals(SimulationCommandCycleStatusResponse.CycleState.IDLE, status.state());
        assertEquals(0L, status.simulatedTimeMs());
        assertEquals(0L, status.nextGenerationAtMs());
    }
}
