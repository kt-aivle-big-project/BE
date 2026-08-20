package com.aivle.be.laro.service;

import com.aivle.be.laro.client.LaroPlanClient;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskCreationService;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LaroPlanExecutionVersionTest {

    @Mock private LaroPlanClient client;
    @Mock private LaroPlanExecutionService executionService;
    @Mock private LaroReplanStateService replanStateService;
    @Mock private SimulationPlaybackService playbackService;
    @Mock private LaroInventoryReservationService inventoryReservationService;
    @Mock private SimulationRunRepository simulationRunRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskCreationService taskCreationService;
    @Mock private WarehouseNodeRepository warehouseNodeRepository;
    @Mock private JdbcTemplate jdbcTemplate;

    private LaroPlanService service;

    @BeforeEach
    void setUp() {
        service = new LaroPlanService(
                client,
                executionService,
                replanStateService,
                playbackService,
                inventoryReservationService,
                simulationRunRepository,
                1_000L
        );
    }

    @Test
    void stalePlanResponseIsReleasedAndNeverInstalled() {
        Long runId = 7L;
        long requestedExecutionVersion = 1L;
        Warehouse warehouse = mock(Warehouse.class);
        SimulationRun run = mock(SimulationRun.class);
        LaroPlanRequest request = mock(LaroPlanRequest.class);
        LaroPlanResponse response = readyResponse(runId, "PLAN-OLD");

        when(run.getWarehouse()).thenReturn(warehouse);
        when(run.getExecutionVersion()).thenReturn(requestedExecutionVersion);
        when(warehouse.isShared()).thenReturn(false);
        when(simulationRunRepository.findByIdWithWarehouse(runId))
                .thenReturn(Optional.of(run));
        when(simulationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(client.plan(runId, request)).thenReturn(response);
        doThrow(new StaleSimulationExecutionException(runId, 1L, 2L))
                .when(executionService)
                .activateIfReady(runId, requestedExecutionVersion, request, response);

        assertThatThrownBy(() -> service.plan(
                runId,
                requestedExecutionVersion,
                request
        )).isInstanceOf(StaleSimulationExecutionException.class);

        verify(inventoryReservationService).failAndReleasePlan(runId, "PLAN-OLD");
    }

    @Test
    void installerRejectsStaleExecutionBeforePreparingTasksOrPlayback() {
        Long runId = 7L;
        SimulationRun currentRun = mock(SimulationRun.class);
        when(currentRun.getExecutionVersion()).thenReturn(2L);
        when(simulationRunRepository.findByIdForUpdate(runId))
                .thenReturn(Optional.of(currentRun));
        LaroPlanExecutionService installer = new LaroPlanExecutionService(
                simulationRunRepository,
                taskRepository,
                taskCreationService,
                warehouseNodeRepository,
                playbackService,
                jdbcTemplate
        );

        assertThatThrownBy(() -> installer.activateIfReady(
                runId,
                1L,
                mock(LaroPlanRequest.class),
                readyResponse(runId, "PLAN-OLD")
        )).isInstanceOf(StaleSimulationExecutionException.class);

        verifyNoInteractions(
                taskRepository,
                taskCreationService,
                warehouseNodeRepository,
                playbackService,
                jdbcTemplate
        );
    }

    @Test
    void replanKeepsRobotsQuiescedWhileHumanReviewIsPending() {
        Long runId = 7L;
        long executionVersion = 2L;
        Warehouse warehouse = mock(Warehouse.class);
        SimulationRun run = mock(SimulationRun.class);
        LaroPlanRequest request = mock(LaroPlanRequest.class);
        SimulationPlaybackService.ActiveAiPlan active =
                new SimulationPlaybackService.ActiveAiPlan(
                        "PLAN-1", 1, 1L, "WH-1", "SIM-1", 10_000L
                );
        LaroPlanResponse pending = pendingReviewResponse(runId);

        when(run.getWarehouse()).thenReturn(warehouse);
        when(run.getExecutionVersion()).thenReturn(executionVersion);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(warehouse.isShared()).thenReturn(false);
        when(simulationRunRepository.findByIdWithWarehouse(runId))
                .thenReturn(Optional.of(run));
        when(simulationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(playbackService.activeAiPlan(runId)).thenReturn(active);
        when(playbackService.isReadyForReplanRequest(runId)).thenReturn(true);
        when(client.replan(
                runId, "PLAN-1", 1, 10_000L, request
        )).thenReturn(pending);

        service.replan(runId, executionVersion, request);

        verify(playbackService).beginQuiescing(runId);
        verify(replanStateService).startQuiescing(runId);
        verify(replanStateService).startReplanning(runId);
        verify(playbackService, never()).cancelQuiescing(runId);
        verify(replanStateService, never()).restoreRunning(runId);
    }

    @Test
    void humanReviewPausesEvenWhenSafeNodeWaitTimesOut() {
        Long runId = 7L;
        long executionVersion = 2L;
        SimulationRun run = mock(SimulationRun.class);
        LaroPlanService zeroTimeoutService = new LaroPlanService(
                client,
                executionService,
                replanStateService,
                playbackService,
                inventoryReservationService,
                simulationRunRepository,
                0L
        );

        when(run.getExecutionVersion()).thenReturn(executionVersion);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(simulationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(playbackService.hasActiveAiPlan(runId)).thenReturn(true);
        when(playbackService.isReadyForReplanRequest(runId)).thenReturn(false);

        assertThatThrownBy(() -> zeroTimeoutService.holdForHumanReview(
                runId,
                executionVersion
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("safe nodes");

        verify(playbackService).beginQuiescing(runId);
        verify(replanStateService).startQuiescing(runId);
        verify(replanStateService).pauseForHumanReview(runId);
    }

    @Test
    void safeNodeWaitFailsImmediatelyWhenPlaybackContextDisappears() {
        Long runId = 7L;
        long executionVersion = 2L;
        SimulationRun run = mock(SimulationRun.class);

        when(run.getExecutionVersion()).thenReturn(executionVersion);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(simulationRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(playbackService.isReadyForReplanRequest(runId)).thenReturn(false);
        when(playbackService.hasActiveAiPlan(runId)).thenReturn(true, false);

        assertThatThrownBy(() -> service.holdForHumanReview(
                runId,
                executionVersion
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("playback context disappeared");

        verify(playbackService).beginQuiescing(runId);
        verify(replanStateService).startQuiescing(runId);
        verify(replanStateService).pauseForHumanReview(runId);
    }

    private LaroPlanResponse readyResponse(Long runId, String planId) {
        LaroPlanResponse.SimulationPlan plan = new LaroPlanResponse.SimulationPlan(
                planId,
                1,
                null,
                "WH-1",
                "SIM-1",
                "READY",
                "INITIAL",
                null,
                100,
                0L,
                0L,
                1_000L,
                1_000L,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );
        LaroPlanResponse.Result result = new LaroPlanResponse.Result(
                "plan_validated",
                "WH-1",
                "SIM-1",
                "structured",
                null,
                null,
                null,
                false,
                plan,
                null,
                null,
                null,
                null,
                List.of()
        );
        return new LaroPlanResponse(
                "v1",
                runId,
                "WH-1",
                1L,
                "REQ-OLD",
                result,
                null,
                null
        );
    }

    private LaroPlanResponse pendingReviewResponse(Long runId) {
        LaroPlanResponse.Result result = new LaroPlanResponse.Result(
                "workflow_hold",
                "WH-1",
                "SIM-1",
                "mixed",
                null,
                "AGENT",
                "router",
                true,
                null,
                null,
                Map.of("interaction_id", "HITL-1"),
                null,
                null,
                List.of()
        );
        return new LaroPlanResponse(
                "v1", runId, "WH-1", 1L, "REQ-HITL", result, null, null
        );
    }
}
