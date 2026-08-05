package com.aivle.be.optimization.service;

import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReoptimizationPlanActivationServiceTest {

    @Test
    void activatesStageAndRunBeforeReturning() {
        Fixture fixture = fixture(
                ReoptimizationPlanStage.Status.DB_APPLIED,
                SimulationRunStatus.REPLANNING
        );

        var result = fixture.service().activate(1L, "replan", 7L);

        assertThat(result.status())
                .isEqualTo(ReoptimizationPlanStage.Status.ACTIVATED);
        verify(fixture.stage()).markActivated();
        verify(fixture.run()).finishReplanning();
        verify(fixture.stageRepository()).flush();
        verify(fixture.runRepository()).flush();
    }

    @Test
    void activatedRunningPairIsIdempotent() {
        Fixture fixture = fixture(
                ReoptimizationPlanStage.Status.ACTIVATED,
                SimulationRunStatus.RUNNING
        );

        var result = fixture.service().activate(1L, "replan", 7L);

        assertThat(result.status())
                .isEqualTo(ReoptimizationPlanStage.Status.ACTIVATED);
        verify(fixture.stage(), never()).markActivated();
        verify(fixture.run(), never()).finishReplanning();
    }

    private Fixture fixture(
            ReoptimizationPlanStage.Status stageStatus,
            SimulationRunStatus runStatus
    ) {
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        ReoptimizationPlanStageRepository stageRepository =
                mock(ReoptimizationPlanStageRepository.class);
        SimulationRun run = mock(SimulationRun.class);
        ReoptimizationPlanStage stage = mock(ReoptimizationPlanStage.class);
        AtomicReference<ReoptimizationPlanStage.Status> status =
                new AtomicReference<>(stageStatus);
        AtomicReference<SimulationRunStatus> currentRunStatus =
                new AtomicReference<>(runStatus);

        when(runRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(run));
        when(stageRepository.findExactForUpdate(1L, "replan", 7L))
                .thenReturn(Optional.of(stage));
        when(run.getStatus()).thenAnswer(ignored -> currentRunStatus.get());
        when(stage.getStatus()).thenAnswer(ignored -> status.get());
        when(stage.getSimulationRun()).thenReturn(run);
        when(run.getId()).thenReturn(1L);
        when(stage.getId()).thenReturn(2L);
        when(stage.getReplanId()).thenReturn("replan");
        when(stage.getSnapshotVersion()).thenReturn(7L);
        when(stage.getSimulationClockMillis()).thenReturn(1_000L);
        when(stage.getTaskPlans()).thenReturn(List.of());
        doAnswer(ignored -> {
            status.set(ReoptimizationPlanStage.Status.ACTIVATED);
            return null;
        }).when(stage).markActivated();
        doAnswer(ignored -> {
            currentRunStatus.set(SimulationRunStatus.RUNNING);
            return null;
        }).when(run).finishReplanning();

        return new Fixture(
                new ReoptimizationPlanActivationService(
                        runRepository,
                        stageRepository
                ),
                runRepository,
                stageRepository,
                run,
                stage
        );
    }

    private record Fixture(
            ReoptimizationPlanActivationService service,
            SimulationRunRepository runRepository,
            ReoptimizationPlanStageRepository stageRepository,
            SimulationRun run,
            ReoptimizationPlanStage stage
    ) {
    }
}
