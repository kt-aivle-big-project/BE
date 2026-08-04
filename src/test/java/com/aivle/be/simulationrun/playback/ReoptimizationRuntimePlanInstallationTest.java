package com.aivle.be.simulationrun.playback;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ReoptimizationRuntimePlanInstallationTest {

    @Test
    void installsAllImmutableRobotPlansWithoutChangingLegacyPlaybackState() {
        Fixture fixture = fixture();
        RobotRuntime first = fixture.first();
        first.setCurrentTaskId(999L);
        first.setPhase(RobotRuntime.Phase.MOVING_TO_END);
        first.setPath(List.of(20L, 30L));
        first.setBusyUntilMillis(4_000L);
        freeze(fixture, "replan-1");
        long frozenClock = fixture.context().getClockMillis();

        RuntimeReoptimizationPlan installed = fixture.service()
                .installReoptimizationPlan(
                        1L,
                        activationPlan(
                                "replan-1",
                                1L,
                                frozenClock,
                                ReoptimizationPlanStage.Status.DB_APPLIED
                        )
                );

        assertThat(installed.robotPlans())
                .extracting(RuntimeRobotPlan::robotId)
                .containsExactly(10L, 20L);
        assertThat(installed.robotPlans().get(0).taskPlans())
                .extracting(RuntimeTaskPlan::taskId)
                .containsExactly(100L, 101L);
        assertThat(installed.robotPlans().get(0).taskPlans().get(0)
                .pathToStart())
                .extracting(
                        RuntimePathStep::nodeId,
                        RuntimePathStep::arrivalTimeMillis,
                        RuntimePathStep::departureTimeMillis
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, 1_000L, 1_000L),
                        org.assertj.core.groups.Tuple.tuple(20L, 2_000L, 2_500L)
                );
        assertThat(installed.robotPlans().get(1).taskPlans()).isEmpty();
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.PLAN_INSTALLED
        );
        assertThat(fixture.context().getInstalledReoptimizationPlan())
                .isEqualTo(installed);
        assertThat(fixture.context().getClockMillis()).isEqualTo(frozenClock);
        assertThat(fixture.context().isReplanRequested()).isTrue();
        assertThat(fixture.context().getActivatedReplanId()).isNull();

        assertThat(first.getStatus()).isEqualTo(RobotStatus.PAUSED);
        assertThat(first.isPausedForReplanning()).isTrue();
        assertThat(first.getCurrentTaskId()).isEqualTo(999L);
        assertThat(first.getPhase())
                .isEqualTo(RobotRuntime.Phase.MOVING_TO_END);
        assertThat(new ArrayList<>(first.getRemainingPath()))
                .containsExactly(20L, 30L);
        assertThat(first.getBusyUntilMillis()).isEqualTo(4_000L);
        assertThat(first.getInstalledReplanId()).isEqualTo("replan-1");
        assertThat(first.getCurrentPlanTaskIndex()).isZero();
        assertThat(first.getCurrentPlanPathSegment()).isEqualTo(
                RobotRuntime.PlannedPathSegment.NONE
        );
        assertThat(first.getCurrentPlanPathStepIndex()).isEqualTo(-1);
        assertThat(fixture.second().getInstalledTaskPlans()).isEmpty();
        verify(first, never()).resumeAfterReplanning();
        verify(fixture.second(), never()).resumeAfterReplanning();

        verifyNoInteractions(
                fixture.runRepository(),
                fixture.taskRepository(),
                fixture.robotRepository(),
                fixture.pathFinder(),
                fixture.stateStore(),
                fixture.messagingTemplate()
        );
    }

    @Test
    void samePlanIsIdempotentAndDoesNotDuplicateRobotQueues() {
        Fixture fixture = fixture();
        freeze(fixture, "replan-1");
        ReoptimizationActivationPlan activation = activationPlan(
                "replan-1",
                1L,
                fixture.context().getClockMillis(),
                ReoptimizationPlanStage.Status.DB_APPLIED
        );

        RuntimeReoptimizationPlan first = fixture.service()
                .installReoptimizationPlan(1L, activation);
        RuntimeReoptimizationPlan second = fixture.service()
                .installReoptimizationPlan(1L, activation);

        assertThat(second).isSameAs(first);
        assertThat(fixture.first().getInstalledTaskPlans())
                .extracting(RuntimeTaskPlan::taskId)
                .containsExactly(100L, 101L);
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.PLAN_INSTALLED
        );
    }

    @Test
    void rejectsUnknownRobotAtomicallyWithoutInstallingAnyRobotPlan() {
        Fixture fixture = fixture();
        freeze(fixture, "replan-1");
        ReoptimizationActivationPlan invalid = new ReoptimizationActivationPlan(
                1L,
                1L,
                "replan-1",
                1L,
                fixture.context().getClockMillis(),
                ReoptimizationPlanStage.Status.DB_APPLIED,
                List.of(taskPlan(999L, 100L, 0, 1_000L))
        );

        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(1L, invalid),
                ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
        );
        assertNothingInstalled(fixture);
    }

    @Test
    void rejectsPlanForErrorRobotWithoutPartialInstallation() {
        Fixture fixture = fixture();
        fixture.second().setStatus(RobotStatus.ERROR);
        freeze(fixture, "replan-1");
        ReoptimizationActivationPlan invalid = new ReoptimizationActivationPlan(
                1L,
                1L,
                "replan-1",
                1L,
                fixture.context().getClockMillis(),
                ReoptimizationPlanStage.Status.DB_APPLIED,
                List.of(
                        taskPlan(10L, 100L, 0, 1_000L),
                        taskPlan(20L, 101L, 0, 4_000L)
                )
        );

        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(1L, invalid),
                ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
        );
        assertNothingInstalled(fixture);
    }

    @Test
    void rejectsMismatchedCorrelationAndDifferentInstalledPlan() {
        Fixture fixture = fixture();
        freeze(fixture, "replan-1");
        long clock = fixture.context().getClockMillis();

        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(
                        1L,
                        activationPlan(
                                "replan-1", 2L, clock,
                                ReoptimizationPlanStage.Status.DB_APPLIED
                        )
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(
                        1L,
                        activationPlan(
                                "other-replan", 1L, clock,
                                ReoptimizationPlanStage.Status.DB_APPLIED
                        )
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        assertNothingInstalled(fixture);

        fixture.service().installReoptimizationPlan(
                1L,
                activationPlan(
                        "replan-1", 1L, clock,
                        ReoptimizationPlanStage.Status.DB_APPLIED
                )
        );
        ReoptimizationActivationPlan changed = new ReoptimizationActivationPlan(
                1L, 1L, "replan-1", 1L, clock,
                ReoptimizationPlanStage.Status.DB_APPLIED,
                List.of(taskPlan(10L, 777L, 0, 1_000L))
        );
        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(1L, changed),
                ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_ALREADY_INSTALLED
        );
    }

    @Test
    void rejectsMissingContextAndNonDbAppliedStage() {
        Fixture fixture = fixture();
        ReoptimizationActivationPlan activation = activationPlan(
                "replan-1", 1L, 1_000L,
                ReoptimizationPlanStage.Status.DB_APPLIED
        );
        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(999L, activation),
                ErrorCode.REOPTIMIZATION_RUNTIME_CONTEXT_NOT_FOUND
        );

        freeze(fixture, "replan-1");
        ReoptimizationActivationPlan staged = activationPlan(
                "replan-1", 1L, fixture.context().getClockMillis(),
                ReoptimizationPlanStage.Status.STAGED
        );
        assertBusinessError(
                () -> fixture.service().installReoptimizationPlan(1L, staged),
                ErrorCode.REOPTIMIZATION_RUNTIME_PLAN_INSTALL_FAILED
        );
        assertNothingInstalled(fixture);
    }

    @Test
    void anotherReplanOwnerCannotReleaseTheInstalledFreeze() {
        Fixture fixture = fixture();
        freeze(fixture, "replan-1");
        fixture.service().installReoptimizationPlan(
                1L,
                activationPlan(
                        "replan-1",
                        1L,
                        fixture.context().getClockMillis(),
                        ReoptimizationPlanStage.Status.DB_APPLIED
                )
        );

        assertBusinessError(
                () -> fixture.context().finishReplanning("other-replan"),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.PLAN_INSTALLED
        );
        assertThat(fixture.first().getStatus()).isEqualTo(RobotStatus.PAUSED);
        assertThat(fixture.second().getStatus()).isEqualTo(RobotStatus.PAUSED);
    }

    private Fixture fixture() {
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore =
                mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        RobotRepository robotRepository = mock(RobotRepository.class);
        WarehousePathFinder pathFinder = mock(WarehousePathFinder.class);
        SimpMessagingTemplate messagingTemplate =
                mock(SimpMessagingTemplate.class);
        SimulationPlaybackService service = new SimulationPlaybackService(
                runRepository,
                stateStore,
                taskRepository,
                robotRepository,
                mock(ChargingStationRepository.class),
                mock(WarehouseNodeRepository.class),
                mock(TaskService.class),
                pathFinder,
                messagingTemplate
        );
        RobotRuntime first = spy(
                new RobotRuntime(10L, 10L, 100, 1.0, 1.0)
        );
        RobotRuntime second = spy(
                new RobotRuntime(20L, 40L, 100, 1.0, 1.0)
        );
        PlaybackContext context = new PlaybackContext(
                1L,
                1L,
                Map.of(),
                Map.of(),
                new ArrayList<>(List.of(first, second)),
                List.of(),
                1.0,
                2.0,
                5.0,
                5.0,
                Map.of()
        );
        context.advanceClock(1_000L);

        @SuppressWarnings("unchecked")
        Map<Long, PlaybackContext> contexts =
                (Map<Long, PlaybackContext>) ReflectionTestUtils.getField(
                        service,
                        "contexts"
                );
        if (contexts == null) {
            throw new AssertionError("playback contexts not found");
        }
        contexts.put(1L, context);
        return new Fixture(
                service,
                context,
                first,
                second,
                runRepository,
                stateStore,
                taskRepository,
                robotRepository,
                pathFinder,
                messagingTemplate
        );
    }

    private void freeze(Fixture fixture, String replanId) {
        PlaybackContext context = fixture.context();
        context.requestReplanning();
        fixture.first().pauseForReplanning();
        fixture.second().pauseForReplanning();
        assertThat(context.areAllRobotsStoppedForReplanning()).isTrue();
        assertThat(context.getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.FROZEN
        );
        assertThat(context.bindReplanId(
                context.getReplanningSnapshotVersion(),
                replanId
        )).isTrue();
    }

    private ReoptimizationActivationPlan activationPlan(
            String replanId,
            Long snapshotVersion,
            Long simulationClockMillis,
            ReoptimizationPlanStage.Status status
    ) {
        return new ReoptimizationActivationPlan(
                1L,
                1L,
                replanId,
                snapshotVersion,
                simulationClockMillis,
                status,
                List.of(
                        taskPlan(10L, 101L, 1, 4_000L),
                        taskPlan(10L, 100L, 0, 1_000L)
                )
        );
    }

    private ReoptimizationActivationPlan.TaskPlanView taskPlan(
            Long robotId,
            Long taskId,
            int sequence,
            long start
    ) {
        return new ReoptimizationActivationPlan.TaskPlanView(
                robotId,
                taskId,
                sequence,
                TaskPlan.ExecutionStage.FULL,
                start,
                start + 2_000L,
                List.of(
                        new ReoptimizationActivationPlan.PathStepView(
                                0, sequence == 0 ? 10L : 30L,
                                start, start
                        ),
                        new ReoptimizationActivationPlan.PathStepView(
                                1, 20L, start + 1_000L,
                                start + 1_500L
                        )
                ),
                List.of(
                        new ReoptimizationActivationPlan.PathStepView(
                                0, 20L, start + 1_500L,
                                start + 1_500L
                        ),
                        new ReoptimizationActivationPlan.PathStepView(
                                1, 30L, start + 2_000L,
                                start + 2_000L
                        )
                )
        );
    }

    private void assertNothingInstalled(Fixture fixture) {
        assertThat(fixture.context().getReplanningState()).isEqualTo(
                PlaybackContext.ReplanningState.FROZEN
        );
        assertThat(fixture.context().getInstalledReoptimizationPlan()).isNull();
        assertThat(fixture.first().getInstalledReplanId()).isNull();
        assertThat(fixture.second().getInstalledReplanId()).isNull();
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }

    private record Fixture(
            SimulationPlaybackService service,
            PlaybackContext context,
            RobotRuntime first,
            RobotRuntime second,
            SimulationRunRepository runRepository,
            SimulationRunStateStore stateStore,
            TaskRepository taskRepository,
            RobotRepository robotRepository,
            WarehousePathFinder pathFinder,
            SimpMessagingTemplate messagingTemplate
    ) {
    }
}
