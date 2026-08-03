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
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.PlaybackContext;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;
import com.aivle.be.simulationrun.playback.RobotRuntime;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.playback.WarehousePathFinder;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                    return successfulResponse(
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
                            ErrorCode.REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED
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
                    return successfulResponse(
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
                            ErrorCode.REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED
                    );
            assertThat(second.get(5, TimeUnit.SECONDS).getErrorCode())
                    .isEqualTo(
                            ErrorCode.REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED
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
                    return successfulResponse(
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
                                            ErrorCode.REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED
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
                readyPlaybackMock();
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
        when(playbackService.captureReplanningSnapshot(1L))
                .thenReturn(new ReplanningSnapshot(
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
                ));
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
                                        ErrorCode.REOPTIMIZATION_PLAN_APPLICATION_NOT_IMPLEMENTED
                                )
                );

        assertThat(fixture.run().status().get())
                .isEqualTo(SimulationRunStatus.REPLANNING);
        assertThat(fixture.context().isReplanRequested()).isTrue();
        assertThat(fixture.runtime().isPausedForReplanning()).isTrue();
        assertThat(new ArrayList<>(fixture.runtime().getRemainingPath()))
                .containsExactly(20L, 30L);
        assertThat(fixture.runtime().getCurrentTaskId()).isEqualTo(100L);
        ReoptimizationOptimizationRequest capturedRequest = aiRequest.get();
        assertThat(capturedRequest).isNotNull();
        assertThat(UUID.fromString(capturedRequest.replanId()))
                .isNotNull();
        assertThat(capturedRequest.simulationRunId()).isEqualTo(1L);
        assertThat(capturedRequest.snapshotVersion()).isEqualTo(1L);
        assertThat(capturedRequest.simulationClockMillis()).isZero();
        assertThat(capturedRequest.warehouseId()).isEqualTo(1L);
        assertThat(capturedRequest.blockedEdgeIds()).isEmpty();
        assertThat(capturedRequest.remainingTasks()).isEmpty();
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
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        verify(fixture.run().run(), never()).finishReplanning();
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
                ErrorCode.REOPTIMIZATION_PLAN_CONTRACT_INVALID
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
        SimpMessagingTemplate messagingTemplate =
                mock(SimpMessagingTemplate.class);
        WarehousePathFinder pathFinder = mock(WarehousePathFinder.class);
        RunFixture run = runFixture(1L);

        when(runRepository.findById(1L))
                .thenReturn(Optional.of(run.run()));
        when(stateStore.findAll(1L)).thenReturn(List.of());
        when(taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        anyLong(),
                        any()
                )).thenReturn(List.of());

        SimulationPlaybackService playbackService =
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
                optimizationClient,
                playbackService,
                transactionManager
        );

        return new RuntimeFixture(
                service,
                optimizationClient,
                pathFinder,
                playbackService,
                run,
                context,
                runtime
        );
    }

    private SimulationPlaybackService readyPlaybackMock() {
        SimulationPlaybackService playbackService =
                mock(SimulationPlaybackService.class);
        when(playbackService.isPlaying(anyLong())).thenReturn(true);
        when(playbackService.requestReplanningStop(anyLong()))
                .thenReturn(true);
        when(playbackService.areAllRobotsStoppedForReplanning(anyLong()))
                .thenReturn(true);
        when(playbackService.captureReplanningSnapshot(anyLong()))
                .thenReturn(new ReplanningSnapshot(
                        1L,
                        1_000L,
                        List.of()
                ));
        return playbackService;
    }

    private ReoptimizationService service(
            SimulationRunRepository runRepository,
            TaskRepository taskRepository,
            OptimizationClient optimizationClient,
            SimulationPlaybackService playbackService,
            TestTransactionManager transactionManager
    ) {
        return new ReoptimizationService(
                runRepository,
                taskRepository,
                optimizationClient,
                playbackService,
                mock(SimpMessagingTemplate.class),
                new TransactionTemplate(transactionManager)
        );
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
        return responseFor(
                request,
                null,
                null,
                null,
                ReoptimizationResponse.Status.SUCCEEDED
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
    }

    private record RunFixture(
            SimulationRun run,
            AtomicReference<SimulationRunStatus> status
    ) {
    }

    private record RuntimeFixture(
            ReoptimizationService service,
            OptimizationClient optimizationClient,
            WarehousePathFinder pathFinder,
            SimulationPlaybackService playbackService,
            RunFixture run,
            PlaybackContext context,
            RobotRuntime runtime
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
