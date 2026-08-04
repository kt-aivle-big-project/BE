package com.aivle.be.optimization.service;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.PathStep;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.PlaybackContext;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;
import com.aivle.be.simulationrun.playback.RobotRuntime;
import com.aivle.be.simulationrun.playback.RuntimeTaskPlan;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.playback.WarehousePathFinder;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReoptimizationServiceTest {

    private static final ReoptimizationRequest REQUEST =
            new ReoptimizationRequest(
                    ReoptimizationReason.MANUAL_REQUEST,
                    null,
                    List.of(),
                    "phase-1-test"
            );

    @Test
    void sameRunAllowsOnlyOneInFlightRequest() throws Exception {
        TestTransactionManager transactionManager =
                new TestTransactionManager();
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore =
                mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationPlaybackService playbackService =
                readyPlaybackMock();
        OptimizationClient optimizationClient =
                mock(OptimizationClient.class);
        RunFixture run = runFixture(1L);

        when(runRepository.findById(1L))
                .thenReturn(Optional.of(run.run()));
        when(stateStore.findAll(1L)).thenReturn(List.of());
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of());

        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        when(optimizationClient.reoptimize(any()))
                .thenAnswer(invocation -> {
                    aiEntered.countDown();
                    if (!releaseAi.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("AI test latch timeout");
                    }
                    return infeasibleResponse(
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            )
                    );
                });

        ReoptimizationService service = service(
                runRepository,
                taskRepository,
                optimizationClient,
                playbackService,
                transactionManager
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BusinessException> first = executor.submit(() ->
                    invokeAndCapture(service, 1L));

            assertThat(aiEntered.await(5, TimeUnit.SECONDS)).isTrue();

            BusinessException second = invokeAndCapture(service, 1L);
            assertThat(second.getErrorCode()).isEqualTo(
                    ErrorCode.REOPTIMIZATION_ALREADY_IN_PROGRESS
            );

            releaseAi.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getErrorCode())
                    .isEqualTo(
                            ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
                    );
        } finally {
            releaseAi.countDown();
            executor.shutdownNow();
        }

        verify(optimizationClient, times(1)).reoptimize(any());
    }

    @Test
    void differentRunsCanEnterAiCallConcurrently() throws Exception {
        TestTransactionManager transactionManager =
                new TestTransactionManager();
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore =
                mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationPlaybackService playbackService =
                readyPlaybackMock();
        OptimizationClient optimizationClient =
                mock(OptimizationClient.class);
        Map<Long, RunFixture> runs = Map.of(
                1L, runFixture(1L),
                2L, runFixture(2L)
        );

        when(runRepository.findById(anyLong()))
                .thenAnswer(invocation -> Optional.of(
                        runs.get(invocation.getArgument(0, Long.class)).run()
                ));
        when(stateStore.findAll(anyLong())).thenReturn(List.of());
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of());

        CountDownLatch bothAiCallsEntered = new CountDownLatch(2);
        CountDownLatch releaseAi = new CountDownLatch(1);
        when(optimizationClient.reoptimize(any()))
                .thenAnswer(invocation -> {
                    bothAiCallsEntered.countDown();
                    if (!releaseAi.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("AI test latch timeout");
                    }
                    return infeasibleResponse(
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            )
                    );
                });

        ReoptimizationService service = service(
                runRepository,
                taskRepository,
                optimizationClient,
                playbackService,
                transactionManager
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BusinessException> first = executor.submit(() ->
                    invokeAndCapture(service, 1L));
            Future<BusinessException> second = executor.submit(() ->
                    invokeAndCapture(service, 2L));

            assertThat(bothAiCallsEntered.await(5, TimeUnit.SECONDS))
                    .isTrue();
            releaseAi.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).getErrorCode())
                    .isEqualTo(
                            ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
                    );
            assertThat(second.get(5, TimeUnit.SECONDS).getErrorCode())
                    .isEqualTo(
                            ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
                    );
        } finally {
            releaseAi.countDown();
            executor.shutdownNow();
        }

        verify(optimizationClient, times(2)).reoptimize(any());
    }

    @Test
    void suspendsOuterTransactionBeforeCallingAiClient() {
        TestTransactionManager transactionManager =
                new TestTransactionManager();
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore =
                mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationPlaybackService playbackService =
                readyPlaybackMock();
        OptimizationClient optimizationClient =
                mock(OptimizationClient.class);
        RunFixture run = runFixture(1L);
        AtomicBoolean aiObservedInactiveTransaction =
                new AtomicBoolean(false);

        when(runRepository.findById(1L))
                .thenReturn(Optional.of(run.run()));
        when(stateStore.findAll(1L)).thenReturn(List.of());
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of());
        when(optimizationClient.reoptimize(any()))
                .thenAnswer(invocation -> {
                    aiObservedInactiveTransaction.set(
                            !TransactionSynchronizationManager
                                    .isActualTransactionActive()
                    );
                    return infeasibleResponse(
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            )
                    );
                });

        ReoptimizationService target = service(
                runRepository,
                taskRepository,
                optimizationClient,
                playbackService,
                transactionManager
        );
        ReoptimizationService proxy = transactionalProxy(
                target,
                transactionManager
        );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(transactionStatus -> {
                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isTrue();

                    assertThatThrownBy(() -> proxy.reoptimize(1L, REQUEST))
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    exception -> assertThat(
                                            exception.getErrorCode()
                                    ).isEqualTo(
                                            ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
                                    )
                            );

                    assertThat(TransactionSynchronizationManager
                            .isActualTransactionActive()).isTrue();
                });

        assertThat(aiObservedInactiveTransaction).isTrue();
    }

    @Test
    void aiFailureKeepsRuntimePausedAndPreservesExistingPlan() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenThrow(new BusinessException(
                        ErrorCode.REOPTIMIZATION_AI_FAILED
                ));

        assertThatThrownBy(() -> fixture.service().reoptimize(1L, REQUEST))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REOPTIMIZATION_AI_FAILED
                                )
                );

        assertThat(fixture.run().status().get())
                .isEqualTo(SimulationRunStatus.REPLANNING);
        assertThat(fixture.context().isReplanRequested()).isTrue();
        assertThat(fixture.runtime().isPausedForReplanning()).isTrue();
        assertThat(fixture.runtime().getStatus())
                .isEqualTo(RobotStatus.PAUSED);
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        verify(fixture.run().run(), never()).finishReplanning();
    }

    @Test
    void failedRobotAfterCompletedPickingIsReportedAsToEnd() {
        TestTransactionManager transactionManager =
                new TestTransactionManager();
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationPlaybackService playbackService =
                readyPlaybackMock(
                        5L,
                        2_000L,
                        List.of(new ReplanningSnapshot.RobotSnapshot(
                                10L,
                                20L,
                                75.0,
                                RobotStatus.ERROR,
                                100L,
                                RobotRuntime.Phase.PICKING,
                                2_000L
                        ))
                );
        OptimizationClient optimizationClient =
                mock(OptimizationClient.class);
        RunFixture run = runFixture(1L);
        AtomicReference<ReoptimizationOptimizationRequest> aiRequest =
                new AtomicReference<>();

        when(runRepository.findById(1L))
                .thenReturn(Optional.of(run.run()));
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of());
        when(optimizationClient.reoptimize(any()))
                .thenAnswer(invocation -> {
                    ReoptimizationOptimizationRequest request =
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                    );
                    aiRequest.set(request);
                    return responseFor(
                            request,
                            null,
                            null,
                            null,
                            ReoptimizationResponse.Status.INFEASIBLE
                    );
                });

        ReoptimizationService service = service(
                runRepository,
                taskRepository,
                optimizationClient,
                playbackService,
                transactionManager
        );

        assertThatThrownBy(() -> service.reoptimize(1L, REQUEST))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
                                )
                );

        assertThat(aiRequest.get().robots()).singleElement()
                .satisfies(robot -> assertThat(robot.remainingStage())
                        .isEqualTo(
                                ReoptimizationOptimizationRequest
                                        .RemainingStage.TO_END
                        ));
    }

    @Test
    void successfulResponseDoesNotResumeOrInvokeWarehousePathFinder() {
        RuntimeFixture fixture = runtimeFixture();
        AtomicReference<ReoptimizationOptimizationRequest> aiRequest =
                new AtomicReference<>();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> {
                    ReoptimizationOptimizationRequest request =
                            invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                            );
                    aiRequest.set(request);
                    return successfulResponse(request);
                });

        assertThatThrownBy(() -> fixture.service().reoptimize(1L, REQUEST))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REOPTIMIZATION_PLAN_RUNTIME_ACTIVATION_NOT_IMPLEMENTED
                                )
                );

        assertThat(fixture.run().status().get())
                .isEqualTo(SimulationRunStatus.REPLANNING);
        assertThat(fixture.context().isReplanRequested()).isTrue();
        assertThat(fixture.runtime().isPausedForReplanning()).isTrue();
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.PLAN_INSTALLED
        );
        assertThat(fixture.runtime().getInstalledReplanId()).isNotNull();
        assertThat(fixture.runtime().getInstalledSnapshotVersion())
                .isEqualTo(1L);
        assertThat(fixture.runtime().getInstalledTaskPlans())
                .extracting(RuntimeTaskPlan::taskId)
                .containsExactly(100L);
        ReoptimizationOptimizationRequest capturedRequest = aiRequest.get();
        assertThat(capturedRequest).isNotNull();
        assertThat(UUID.fromString(capturedRequest.replanId()))
                .isNotNull();
        assertThat(capturedRequest.simulationRunId()).isEqualTo(1L);
        assertThat(capturedRequest.snapshotVersion()).isEqualTo(1L);
        assertThat(capturedRequest.simulationClockMillis()).isZero();
        assertThat(capturedRequest.warehouseId()).isEqualTo(1L);
        assertThat(capturedRequest.blockedEdgeIds()).isEmpty();
        assertThat(capturedRequest.remainingTasks()).singleElement()
                .satisfies(task -> {
                    assertThat(task.taskId()).isEqualTo(100L);
                    assertThat(task.startNodeId()).isEqualTo(20L);
                    assertThat(task.endNodeId()).isEqualTo(30L);
                });
        assertThat(capturedRequest.robots()).singleElement()
                .satisfies(robot -> {
                    assertThat(robot.robotId()).isEqualTo(10L);
                    assertThat(robot.currentNodeId()).isEqualTo(10L);
                    assertThat(robot.batteryLevel()).isEqualTo(100.0);
                    assertThat(robot.status()).isEqualTo("PAUSED");
                    assertThat(robot.currentTaskId()).isEqualTo(100L);
                    assertThat(robot.runtimePhase()).isEqualTo("IDLE");
                    assertThat(robot.remainingStage()).isEqualTo(
                            ReoptimizationOptimizationRequest
                                    .RemainingStage.IDLE
                    );
                });
        ArgumentCaptor<ReoptimizationPlanStageCommand> stageCommand =
                ArgumentCaptor.forClass(
                        ReoptimizationPlanStageCommand.class
                );
        verify(fixture.planStagingService(), times(1))
                .stage(stageCommand.capture());
        verify(fixture.planApplicationService(), times(1))
                .apply(1L, capturedRequest.replanId(), 1L);
        verify(fixture.playbackService(), times(1))
                .installReoptimizationPlan(
                        anyLong(),
                        any(ReoptimizationActivationPlan.class)
                );
        assertThat(stageCommand.getValue().simulationRunId()).isEqualTo(1L);
        assertThat(stageCommand.getValue().replanId())
                .isEqualTo(capturedRequest.replanId());
        assertThat(stageCommand.getValue().snapshotVersion()).isEqualTo(1L);
        assertThat(stageCommand.getValue().taskPlans()).singleElement()
                .satisfies(plan -> {
                    assertThat(plan.robotId()).isEqualTo(10L);
                    assertThat(plan.taskId()).isEqualTo(100L);
                    assertThat(plan.pathSteps()).hasSize(4);
                });
        verify(fixture.optimizationClient(), times(1)).reoptimize(any());
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        verify(fixture.run().run(), never()).finishReplanning();
        assertTaskPlanUnchanged(fixture.task());
    }

    @Test
    void infeasibleResponseKeepsDatabaseAndRuntimeReplanning() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        ),
                        null,
                        null,
                        null,
                        ReoptimizationResponse.Status.INFEASIBLE
                ));

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
        );
        verify(fixture.planStagingService(), never()).stage(any());
    }

    @Test
    void failedResponseDoesNotCreateStagingPlan() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        ),
                        null,
                        null,
                        null,
                        ReoptimizationResponse.Status.FAILED
                ));

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_AI_FAILED
        );
    }

    @Test
    void stagingFailureKeepsDatabaseAndRuntimeFrozen() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> successfulResponse(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        )
                ));
        doThrow(new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_FAILED
        )).when(fixture.planStagingService()).stage(any());

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_FAILED,
                true
        );
    }

    @Test
    void runtimeInstallationFailureKeepsDbAppliedPlanAndRuntimeFrozen() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> successfulResponse(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        )
                ));
        when(fixture.planApplicationService()
                .apply(anyLong(), any(), anyLong()))
                .thenAnswer(invocation -> new ReoptimizationActivationPlan(
                        1L,
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        0L,
                        ReoptimizationPlanStage.Status.DB_APPLIED,
                        List.of(
                                new ReoptimizationActivationPlan.TaskPlanView(
                                        999L,
                                        100L,
                                        0,
                                        TaskPlan.ExecutionStage.FULL,
                                        0L,
                                        1_000L,
                                        List.of(
                                                new ReoptimizationActivationPlan.PathStepView(
                                                        0, 10L, 0L, 0L
                                                )
                                        ),
                                        List.of(
                                                new ReoptimizationActivationPlan.PathStepView(
                                                        0, 30L, 1_000L, 1_000L
                                                )
                                        )
                                )
                        )
                ));

        assertThatThrownBy(() -> fixture.service().reoptimize(1L, REQUEST))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
                                )
                );

        assertThat(fixture.run().status().get())
                .isEqualTo(SimulationRunStatus.REPLANNING);
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.FROZEN
        );
        assertThat(fixture.context().getInstalledReoptimizationPlan()).isNull();
        assertThat(fixture.runtime().getInstalledReplanId()).isNull();
        assertThat(fixture.runtime().getStatus()).isEqualTo(RobotStatus.PAUSED);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
        verify(fixture.planStagingService(), times(1)).stage(any());
        verify(fixture.planApplicationService(), times(1))
                .apply(anyLong(), any(), anyLong());
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        verify(fixture.run().run(), never()).finishReplanning();
        assertTaskPlanUnchanged(fixture.task());
    }

    @Test
    void invalidPlanContractDoesNotResumeOrInvokeWarehousePathFinder() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> {
                    ReoptimizationOptimizationRequest request =
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            );
                    TaskPlan invalidPlan = new TaskPlan(
                            10L,
                            999L,
                            0,
                            TaskPlan.ExecutionStage.FULL,
                            List.of(new PathStep(10L, 0L, 0L)),
                            List.of(new PathStep(20L, 1_000L, 1_000L)),
                            0L,
                            1_000L
                    );

                    return new ReoptimizationResponse(
                            "request-1",
                            request.replanId(),
                            request.simulationRunId(),
                            request.snapshotVersion(),
                            ReoptimizationResponse.Status.SUCCEEDED,
                            List.of(invalidPlan),
                            List.of(),
                            "unknown task contract violation"
                    );
                });

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_PLAN_TASK_COVERAGE_INVALID
        );
    }

    @Test
    void toEndWithNonZeroSequenceKeepsDatabaseAndRuntimeFrozen() {
        RuntimeFixture fixture = runtimeFixture();
        fixture.runtime().setPhase(RobotRuntime.Phase.MOVING_TO_END);
        fixture.runtime().pauseForReplanning();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> {
                    ReoptimizationOptimizationRequest request =
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            );
                    TaskPlan invalidPlan = new TaskPlan(
                            10L,
                            100L,
                            1,
                            TaskPlan.ExecutionStage.TO_END,
                            List.of(),
                            List.of(
                                    new PathStep(10L, 0L, 0L),
                                    new PathStep(30L, 2_000L, 2_000L)
                            ),
                            0L,
                            2_000L
                    );

                    return new ReoptimizationResponse(
                            "request-1",
                            request.replanId(),
                            request.simulationRunId(),
                            request.snapshotVersion(),
                            ReoptimizationResponse.Status.SUCCEEDED,
                            List.of(invalidPlan),
                            List.of(),
                            "TO_END must start with sequence zero"
                    );
                });

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_PLAN_CONTRACT_INVALID
        );
    }

    @Test
    void rejectsPlanWhenRuntimeSnapshotChangesDuringAiCall() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> {
                    fixture.runtime().setBatteryLevel(90.0);
                    return infeasibleResponse(
                            invocation.getArgument(
                                    0,
                                    ReoptimizationOptimizationRequest.class
                            )
                    );
                });

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
    }

    @Test
    void rejectsMismatchedReplanId() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        ),
                        "different-replan-id",
                        null,
                        null,
                        ReoptimizationResponse.Status.SUCCEEDED
                ));

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_RESPONSE_CORRELATION_MISMATCH
        );
    }

    @Test
    void rejectsMismatchedSimulationRunId() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        ),
                        null,
                        999L,
                        null,
                        ReoptimizationResponse.Status.SUCCEEDED
                ));

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_RESPONSE_CORRELATION_MISMATCH
        );
    }

    @Test
    void rejectsMismatchedSnapshotVersion() {
        RuntimeFixture fixture = runtimeFixture();
        when(fixture.optimizationClient().reoptimize(any()))
                .thenAnswer(invocation -> responseFor(
                        invocation.getArgument(
                                0,
                                ReoptimizationOptimizationRequest.class
                        ),
                        null,
                        null,
                        999L,
                        ReoptimizationResponse.Status.SUCCEEDED
                ));

        assertReoptimizationRejectedAndFrozen(
                fixture,
                ErrorCode.REOPTIMIZATION_RESPONSE_CORRELATION_MISMATCH
        );
    }

    @Test
    void failedRobotKeepsCurrentTaskPhaseAndPath() {
        RuntimeFixture fixture = runtimeFixture();
        fixture.runtime().setPhase(RobotRuntime.Phase.MOVING_TO_START);

        assertThat(fixture.playbackService().markRobotError(1L, 10L))
                .isTrue();

        assertThat(fixture.runtime().getStatus())
                .isEqualTo(RobotStatus.ERROR);
        assertThat(fixture.runtime().getPhase())
                .isEqualTo(RobotRuntime.Phase.MOVING_TO_START);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
    }

    private RuntimeFixture runtimeFixture() {
        TestTransactionManager transactionManager =
                new TestTransactionManager();
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore =
                mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        OptimizationClient optimizationClient =
                mock(OptimizationClient.class);
        ReoptimizationPlanStagingService planStagingService =
                mock(ReoptimizationPlanStagingService.class);
        ReoptimizationPlanApplicationService planApplicationService =
                successfulPlanApplicationService();
        when(planStagingService.stage(any())).thenAnswer(invocation -> {
            verify(optimizationClient, times(1)).reoptimize(any());
            return 1L;
        });
        SimpMessagingTemplate messagingTemplate =
                mock(SimpMessagingTemplate.class);
        WarehousePathFinder pathFinder = mock(WarehousePathFinder.class);
        WarehouseNodeRepository warehouseNodeRepository =
                mock(WarehouseNodeRepository.class);
        WarehouseEdgeRepository warehouseEdgeRepository =
                mock(WarehouseEdgeRepository.class);
        RunFixture run = runFixture(1L);
        WarehouseNode node10 = node(10L);
        WarehouseNode node20 = node(20L);
        WarehouseNode node30 = node(30L);
        Task task = mock(Task.class);
        WarehouseEdge edge10To20 = edge(1L, node10, node20);
        WarehouseEdge edge20To30 = edge(2L, node20, node30);

        when(runRepository.findById(1L))
                .thenReturn(Optional.of(run.run()));
        when(stateStore.findAll(1L)).thenReturn(List.of());
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of(task));
        when(task.getId()).thenReturn(100L);
        when(task.getRobot()).thenReturn(null);
        when(task.getStartNode()).thenReturn(node20);
        when(task.getEndNode()).thenReturn(node30);
        when(task.getTaskType()).thenReturn(TaskType.OUTBOUND);
        when(task.getStatus()).thenReturn(TaskStatus.IN_PROGRESS);
        when(warehouseNodeRepository.findAllByWarehouse_Id(1L))
                .thenReturn(List.of(node10, node20, node30));
        when(warehouseEdgeRepository
                .findAllByFromNode_Warehouse_Id(1L))
                .thenReturn(List.of(edge10To20, edge20To30));

        SimulationPlaybackService playbackService = spy(
                new SimulationPlaybackService(
                        runRepository,
                        stateStore,
                        taskRepository,
                        mock(RobotRepository.class),
                        mock(ChargingStationRepository.class),
                        mock(WarehouseNodeRepository.class),
                        mock(TaskService.class),
                        pathFinder,
                        messagingTemplate
                )
        );

        RobotRuntime runtime = new RobotRuntime(
                10L,
                10L,
                100,
                1.0,
                1.0
        );
        runtime.setCurrentTaskId(100L);
        runtime.setPath(List.of(20L, 30L));

        PlaybackContext context = new PlaybackContext(
                1L,
                1L,
                Map.of(),
                Map.of(),
                new ArrayList<>(List.of(runtime)),
                List.of(),
                1.0,
                2.0,
                5.0,
                5.0,
                Map.of()
        );

        @SuppressWarnings("unchecked")
        Map<Long, PlaybackContext> contexts =
                (Map<Long, PlaybackContext>) ReflectionTestUtils
                        .getField(playbackService, "contexts");
        if (contexts == null) {
            throw new AssertionError("playback contexts not found");
        }
        contexts.put(1L, context);

        ReoptimizationService service = service(
                runRepository,
                taskRepository,
                warehouseNodeRepository,
                warehouseEdgeRepository,
                optimizationClient,
                planStagingService,
                planApplicationService,
                playbackService,
                transactionManager
        );

        return new RuntimeFixture(
                service,
                optimizationClient,
                planStagingService,
                planApplicationService,
                pathFinder,
                playbackService,
                run,
                context,
                runtime,
                task
        );
    }

    private SimulationPlaybackService readyPlaybackMock() {
        return readyPlaybackMock(1L, 1_000L, List.of());
    }

    private SimulationPlaybackService readyPlaybackMock(
            Long snapshotVersion,
            Long simulationClockMillis,
            List<ReplanningSnapshot.RobotSnapshot> robots
    ) {
        SimulationPlaybackService playbackService =
                mock(SimulationPlaybackService.class);
        Map<Long, String> boundReplanIds = new ConcurrentHashMap<>();
        when(playbackService.isPlaying(anyLong())).thenReturn(true);
        when(playbackService.requestReplanningStop(anyLong()))
                .thenReturn(true);
        when(playbackService.areAllRobotsStoppedForReplanning(anyLong()))
                .thenReturn(true);
        when(playbackService.bindReplanId(
                anyLong(),
                anyLong(),
                any()
        )).thenAnswer(invocation -> {
            boundReplanIds.put(
                    invocation.getArgument(0, Long.class),
                    invocation.getArgument(2, String.class)
            );
            return true;
        });
        when(playbackService.captureReplanningSnapshot(anyLong()))
                .thenAnswer(invocation -> {
                    Long simulationRunId = invocation.getArgument(
                            0,
                            Long.class
                    );
                    return new ReplanningSnapshot(
                        boundReplanIds.get(simulationRunId),
                        simulationRunId,
                        snapshotVersion,
                        simulationClockMillis,
                        robots
                    );
                });
        return playbackService;
    }

    private ReoptimizationService service(
            SimulationRunRepository runRepository,
            TaskRepository taskRepository,
            OptimizationClient optimizationClient,
            SimulationPlaybackService playbackService,
            TestTransactionManager transactionManager
    ) {
        WarehouseNodeRepository warehouseNodeRepository =
                mock(WarehouseNodeRepository.class);
        WarehouseEdgeRepository warehouseEdgeRepository =
                mock(WarehouseEdgeRepository.class);
        when(warehouseNodeRepository.findAllByWarehouse_Id(anyLong()))
                .thenReturn(List.of());
        when(warehouseEdgeRepository
                .findAllByFromNode_Warehouse_Id(anyLong()))
                .thenReturn(List.of());

        return new ReoptimizationService(
                runRepository,
                taskRepository,
                warehouseNodeRepository,
                warehouseEdgeRepository,
                optimizationClient,
                mock(ReoptimizationPlanStagingService.class),
                successfulPlanApplicationService(),
                playbackService,
                mock(SimpMessagingTemplate.class),
                new TransactionTemplate(transactionManager)
        );
    }

    private ReoptimizationService service(
            SimulationRunRepository runRepository,
            TaskRepository taskRepository,
            WarehouseNodeRepository warehouseNodeRepository,
            WarehouseEdgeRepository warehouseEdgeRepository,
            OptimizationClient optimizationClient,
            ReoptimizationPlanStagingService planStagingService,
            ReoptimizationPlanApplicationService planApplicationService,
            SimulationPlaybackService playbackService,
            TestTransactionManager transactionManager
    ) {
        return new ReoptimizationService(
                runRepository,
                taskRepository,
                warehouseNodeRepository,
                warehouseEdgeRepository,
                optimizationClient,
                planStagingService,
                planApplicationService,
                playbackService,
                mock(SimpMessagingTemplate.class),
                new TransactionTemplate(transactionManager)
        );
    }

    private ReoptimizationPlanApplicationService
    successfulPlanApplicationService() {
        ReoptimizationPlanApplicationService service =
                mock(ReoptimizationPlanApplicationService.class);
        when(service.apply(anyLong(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    Long simulationRunId = invocation.getArgument(0);
                    String replanId = invocation.getArgument(1);
                    Long snapshotVersion = invocation.getArgument(2);
                    ReoptimizationActivationPlan.TaskPlanView taskPlan =
                            new ReoptimizationActivationPlan.TaskPlanView(
                                    10L,
                                    100L,
                                    0,
                                    TaskPlan.ExecutionStage.FULL,
                                    0L,
                                    2_000L,
                                    List.of(
                                            new ReoptimizationActivationPlan.PathStepView(
                                                    0, 10L, 0L, 0L
                                            ),
                                            new ReoptimizationActivationPlan.PathStepView(
                                                    1, 20L, 1_000L, 1_000L
                                            )
                                    ),
                                    List.of(
                                            new ReoptimizationActivationPlan.PathStepView(
                                                    0, 20L, 1_000L, 1_000L
                                            ),
                                            new ReoptimizationActivationPlan.PathStepView(
                                                    1, 30L, 2_000L, 2_000L
                                            )
                                    )
                            );
                    return new ReoptimizationActivationPlan(
                            1L,
                            simulationRunId,
                            replanId,
                            snapshotVersion,
                            0L,
                            ReoptimizationPlanStage.Status.DB_APPLIED,
                            List.of(taskPlan)
                    );
                });
        return service;
    }

    private ReoptimizationService transactionalProxy(
            ReoptimizationService target,
            TestTransactionManager transactionManager
    ) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource()
        );
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return (ReoptimizationService) proxyFactory.getProxy();
    }

    private RunFixture runFixture(Long warehouseId) {
        SimulationRun run = mock(SimulationRun.class);
        Warehouse warehouse = mock(Warehouse.class);
        AtomicReference<SimulationRunStatus> status =
                new AtomicReference<>(SimulationRunStatus.RUNNING);

        when(run.getStatus()).thenAnswer(invocation -> status.get());
        when(run.getWarehouse()).thenReturn(warehouse);
        when(warehouse.getId()).thenReturn(warehouseId);
        doAnswer(invocation -> {
            status.set(SimulationRunStatus.REPLANNING);
            return null;
        }).when(run).startReplanning();

        return new RunFixture(run, status);
    }

    private BusinessException invokeAndCapture(
            ReoptimizationService service,
            Long simulationRunId
    ) {
        try {
            service.reoptimize(simulationRunId, REQUEST);
        } catch (BusinessException exception) {
            return exception;
        }
        throw new AssertionError("BusinessException was not thrown");
    }

    private ReoptimizationResponse successfulResponse(
            ReoptimizationOptimizationRequest request
    ) {
        ReoptimizationOptimizationRequest.RobotStateInput robot =
                request.robots().get(0);
        ReoptimizationOptimizationRequest.TaskInput task =
                request.remainingTasks().get(0);
        long start = request.simulationClockMillis();
        TaskPlan plan = new TaskPlan(
                robot.robotId(),
                task.taskId(),
                0,
                TaskPlan.ExecutionStage.FULL,
                List.of(
                        new PathStep(
                                robot.currentNodeId(),
                                start,
                                start
                        ),
                        new PathStep(
                                task.startNodeId(),
                                start + 1_000L,
                                start + 1_000L
                        )
                ),
                List.of(
                        new PathStep(
                                task.startNodeId(),
                                start + 1_000L,
                                start + 1_000L
                        ),
                        new PathStep(
                                task.endNodeId(),
                                start + 2_000L,
                                start + 2_000L
                        )
                ),
                start,
                start + 2_000L
        );

        return new ReoptimizationResponse(
                "request-1",
                request.replanId(),
                request.simulationRunId(),
                request.snapshotVersion(),
                ReoptimizationResponse.Status.SUCCEEDED,
                List.of(plan),
                List.of(),
                null
        );
    }

    private WarehouseNode node(Long id) {
        WarehouseNode node = mock(WarehouseNode.class);
        when(node.getId()).thenReturn(id);
        return node;
    }

    private WarehouseEdge edge(
            Long id,
            WarehouseNode from,
            WarehouseNode to
    ) {
        WarehouseEdge edge = mock(WarehouseEdge.class);
        when(edge.getId()).thenReturn(id);
        when(edge.getFromNode()).thenReturn(from);
        when(edge.getToNode()).thenReturn(to);
        when(edge.getDirectionType())
                .thenReturn(WarehouseEdge.DirectionType.A_TO_B);
        return edge;
    }

    private ReoptimizationResponse infeasibleResponse(
            ReoptimizationOptimizationRequest request
    ) {
        return responseFor(
                request,
                null,
                null,
                null,
                ReoptimizationResponse.Status.INFEASIBLE
        );
    }

    private ReoptimizationResponse responseFor(
            ReoptimizationOptimizationRequest request,
            String replanId,
            Long simulationRunId,
            Long snapshotVersion,
            ReoptimizationResponse.Status status
    ) {
        return new ReoptimizationResponse(
                "request-1",
                replanId == null ? request.replanId() : replanId,
                simulationRunId == null
                        ? request.simulationRunId()
                        : simulationRunId,
                snapshotVersion == null
                        ? request.snapshotVersion()
                        : snapshotVersion,
                status,
                List.of(),
                List.of(),
                null
        );
    }

    private void assertReoptimizationRejectedAndFrozen(
            RuntimeFixture fixture,
            ErrorCode expectedErrorCode
    ) {
        assertReoptimizationRejectedAndFrozen(
                fixture,
                expectedErrorCode,
                false
        );
    }

    private void assertReoptimizationRejectedAndFrozen(
            RuntimeFixture fixture,
            ErrorCode expectedErrorCode,
            boolean stagingInvoked
    ) {
        assertThatThrownBy(() -> fixture.service().reoptimize(1L, REQUEST))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expectedErrorCode)
                );

        assertThat(fixture.run().status().get())
                .isEqualTo(SimulationRunStatus.REPLANNING);
        assertThat(fixture.context().isReplanRequested()).isTrue();
        assertThat(fixture.runtime().isPausedForReplanning()).isTrue();
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        verify(fixture.run().run(), never()).finishReplanning();
        if (stagingInvoked) {
            verify(fixture.planStagingService(), times(1)).stage(any());
        } else {
            verify(fixture.planStagingService(), never()).stage(any());
        }
        verify(fixture.planApplicationService(), never())
                .apply(anyLong(), any(), anyLong());
        assertTaskPlanUnchanged(fixture.task());
    }

    private void assertTaskPlanUnchanged(Task task) {
        assertThat(task.getRobot()).isNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        verify(task, never()).assignRobot(any());
        verify(task, never()).reassignRobot(any());
        verify(task, never()).resetForReplay();
        verify(task, never()).start();
        verify(task, never()).complete();
        verify(task, never()).fail();
        verify(task, never()).cancel();
    }

    private record RunFixture(
            SimulationRun run,
            AtomicReference<SimulationRunStatus> status
    ) {
    }

    private record RuntimeFixture(
            ReoptimizationService service,
            OptimizationClient optimizationClient,
            ReoptimizationPlanStagingService planStagingService,
            ReoptimizationPlanApplicationService planApplicationService,
            WarehousePathFinder pathFinder,
            SimulationPlaybackService playbackService,
            RunFixture run,
            PlaybackContext context,
            RobotRuntime runtime,
            Task task
    ) {
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        private final ThreadLocal<Boolean> active =
                ThreadLocal.withInitial(() -> false);

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active.get();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            active.remove();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            active.remove();
        }

        @Override
        protected Object doSuspend(Object transaction) {
            boolean suspended = active.get();
            active.remove();
            return suspended;
        }

        @Override
        protected void doResume(
                Object transaction,
                Object suspendedResources
        ) {
            if (Boolean.TRUE.equals(suspendedResources)) {
                active.set(true);
            }
        }
    }
}
