package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandGenerationService;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.dto.LaroHumanReviewRequest;
import com.aivle.be.laro.dto.LaroLowBatteryContext;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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
    private SimulationRunPlanSnapshotStore planSnapshotStore;
    @Mock
    private SimulationRun run;

    @InjectMocks
    private SimulationCommandCycleService service;

    @Test
    void stopRemovesOldClockAndReturnsZeroedIdleStatus() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getExecutionVersion()).thenReturn(2L);
        when(run.getStatus()).thenReturn(SimulationRunStatus.CREATED);

        service.configure(1L, FulfillmentCommandGenerateRequest.automatic());
        service.stop(1L);

        SimulationCommandCycleStatusResponse status = service.status(1L);
        assertFalse(status.active());
        assertEquals(2L, status.executionVersion());
        assertEquals(SimulationCommandCycleStatusResponse.CycleState.IDLE, status.state());
        assertEquals(0L, status.simulatedTimeMs());
        assertEquals(0L, status.nextGenerationAtMs());
    }

    @Test
    void configurationChangesReplanIntervalAndAverageWorkload() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getExecutionVersion()).thenReturn(2L);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);

        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.AUTO,
                null,
                null,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.AUTO,
                CommandPolicyProfile.AUTO,
                false,
                false,
                180,
                2.5
        );

        SimulationCommandCycleStatusResponse status = service.configure(1L, request);

        assertEquals(180, status.generationIntervalSeconds());
        assertEquals(2.5, status.averageTasksPerRobot());
        assertEquals(180_000L, status.nextGenerationAtMs());
    }

    @Test
    void userCommandRunsExistingReplanCycleWithOneShotIntent() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getExecutionVersion()).thenReturn(2L);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(laroPlanService.preflight(1L)).thenReturn(new LaroPreflightResponse(
                "READY", true, 1L, "WH-1", 1L,
                Map.of(), Map.of(), "redis", List.of()
        ));

        LaroPlanRequest.StructuredOperation operation = mock(
                LaroPlanRequest.StructuredOperation.class
        );
        LaroPlanRequest original = new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REQ-1", List.of(operation), Map.of(), null
                ),
                null,
                null,
                null
        );
        FulfillmentCommandGenerateResponse.FrontView frontView = mock(
                FulfillmentCommandGenerateResponse.FrontView.class
        );
        when(frontView.requestId()).thenReturn("REQ-1");
        when(commandGenerationService.generate(eq(1L), any()))
                .thenReturn(new FulfillmentCommandGenerateResponse(original, frontView));
        when(playbackService.hasActiveAiPlan(1L)).thenReturn(true);
        when(laroPlanService.replan(eq(1L), eq(2L), any()))
                .thenReturn(mock(LaroPlanResponse.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        SimulationCommandCycleStatusResponse status = service.triggerUserCommand(
                1L,
                2L,
                "  출고 작업을 우선 처리해 줘  "
        );

        ArgumentCaptor<LaroPlanRequest> requestCaptor = ArgumentCaptor.forClass(
                LaroPlanRequest.class
        );
        verify(laroPlanService).replan(eq(1L), eq(2L), requestCaptor.capture());
        assertEquals("출고 작업을 우선 처리해 줘", requestCaptor.getValue().userCommand());
        assertEquals("출고 작업을 우선 처리해 줘", status.userCommand());
        assertEquals(SimulationCommandCycleStatusResponse.CycleState.COMPLETE, status.state());
    }

    @Test
    void lowBatteryPlaybackSignalUsesExistingReplanWithRuleReason() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getExecutionVersion()).thenReturn(2L);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(run.getSimulationSpeed()).thenReturn(1.0);
        when(laroPlanService.preflight(1L)).thenReturn(new LaroPreflightResponse(
                "READY", true, 1L, "WH-1", 1L,
                Map.of(), Map.of(), "redis", List.of()
        ));

        LaroPlanRequest.StructuredOperation operation = mock(
                LaroPlanRequest.StructuredOperation.class
        );
        LaroPlanRequest planRequest = new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REQ-BATTERY", List.of(operation), Map.of(), null
                ),
                null,
                null,
                null
        );
        FulfillmentCommandGenerateResponse.FrontView frontView = mock(
                FulfillmentCommandGenerateResponse.FrontView.class
        );
        when(frontView.requestId()).thenReturn("REQ-BATTERY");
        when(commandGenerationService.generate(eq(1L), any()))
                .thenReturn(new FulfillmentCommandGenerateResponse(planRequest, frontView));
        when(playbackService.hasActiveAiPlan(1L)).thenReturn(false, true);
        when(laroPlanService.plan(eq(1L), eq(2L), any()))
                .thenReturn(mock(LaroPlanResponse.class));
        when(laroPlanService.replan(
                eq(1L), eq(2L), any(), eq("LOW_BATTERY"), any()
        ))
                .thenReturn(mock(LaroPlanResponse.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        service.start(1L);
        when(playbackService.pendingLowBatteryReplanRequests()).thenReturn(List.of(
                new SimulationPlaybackService.LowBatteryReplanRequest(
                        1L, 55L, 20, 20, 10L, "C01", 2792L, false, 1_250L
                )
        ));

        service.tick();

        verify(playbackService).acknowledgeLowBatteryReplanRequest(1L, 55L);
        ArgumentCaptor<LaroLowBatteryContext> contextCaptor =
                ArgumentCaptor.forClass(LaroLowBatteryContext.class);
        verify(laroPlanService).replan(
                eq(1L), eq(2L), any(), eq("LOW_BATTERY"), contextCaptor.capture()
        );
        assertEquals(55L, contextCaptor.getValue().robotId());
        assertEquals("C01", contextCaptor.getValue().currentNodeCode());
        assertEquals(2792L, contextCaptor.getValue().currentTaskId());
        assertEquals(1_250L, contextCaptor.getValue().stoppedAtSimTimeMs());
        verify(commandGenerationService, times(1)).generate(eq(1L), any());
        assertEquals(
                "LOW_BATTERY_REPLAN",
                service.status(1L).planningMode()
        );
    }

    @Test
    void planFailureBlocksFollowingCyclesUntilHumanReviewRetries() {
        when(simulationRunRepository.findById(1L)).thenReturn(Optional.of(run));
        when(run.getGenerationIntervalSeconds()).thenReturn(300);
        when(run.getExecutionVersion()).thenReturn(2L);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(laroPlanService.preflight(1L)).thenReturn(new LaroPreflightResponse(
                "READY", true, 1L, "WH-1", 1L,
                Map.of(), Map.of(), "redis", List.of()
        ));

        LaroPlanRequest.StructuredOperation operation = mock(
                LaroPlanRequest.StructuredOperation.class
        );
        LaroPlanRequest planRequest = new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REQ-ERROR", List.of(operation), Map.of(), null
                ),
                null,
                null,
                null
        );
        FulfillmentCommandGenerateResponse.FrontView frontView = mock(
                FulfillmentCommandGenerateResponse.FrontView.class
        );
        when(frontView.requestId()).thenReturn("REQ-ERROR");
        when(commandGenerationService.generate(eq(1L), any()))
                .thenReturn(new FulfillmentCommandGenerateResponse(planRequest, frontView));
        when(playbackService.hasActiveAiPlan(1L)).thenReturn(false);
        when(laroPlanService.plan(eq(1L), eq(2L), any()))
                .thenThrow(new IllegalStateException(
                        "504 Gateway Timeout: upstream request timeout"
                ))
                .thenReturn(mock(LaroPlanResponse.class));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        SimulationCommandCycleStatusResponse failed = service.triggerNow(1L);

        assertEquals(
                SimulationCommandCycleStatusResponse.CycleState.REVIEW_REQUIRED,
                failed.state()
        );
        assertNotNull(failed.pendingHumanInteraction());
        assertEquals(
                "AI_GATEWAY_TIMEOUT",
                failed.pendingHumanInteraction().get("reason_code")
        );
        assertEquals(
                "AI 계획 응답이 제한 시간 안에 도착하지 않았습니다. 일시적인 지연일 수 있으니 잠시 후 다시 시도해 주세요.",
                failed.pendingHumanInteraction().get("prompt")
        );
        assertEquals(
                "504 Gateway Timeout: upstream request timeout",
                failed.pendingHumanInteraction().get("technical_detail")
        );
        verify(laroPlanService).holdForHumanReview(1L, 2L);

        service.triggerNow(1L);
        verify(commandGenerationService, times(1)).generate(eq(1L), any());

        String interactionId = failed.pendingHumanInteraction()
                .get("interaction_id")
                .toString();
        SimulationCommandCycleStatusResponse retried = service.respondToHumanReview(
                1L,
                interactionId,
                new LaroHumanReviewRequest(
                        "SELECT",
                        "RETRY_NOW",
                        List.of(),
                        null,
                        "일시적인 게이트웨이 오류를 확인함",
                        2L
                ),
                "USER-1"
        );

        assertEquals(
                SimulationCommandCycleStatusResponse.CycleState.COMPLETE,
                retried.state()
        );
        verify(laroPlanService).resumeForHumanReviewDecision(1L, 2L);
        verify(commandGenerationService, times(2)).generate(eq(1L), any());
        verify(laroPlanService, times(2)).plan(eq(1L), eq(2L), any());
    }
}
