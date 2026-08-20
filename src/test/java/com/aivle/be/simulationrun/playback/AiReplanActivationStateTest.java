package com.aivle.be.simulationrun.playback;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiReplanActivationStateTest {

    @Test
    void heldRuntimeNodeWinsOverStaleMovingProjection() {
        AiPlaybackContext oldContext = context(
                "PLAN-1", timeline(101L, 20L));
        oldContext.requestQuiesce();
        AiPlaybackContext nextContext = context(
                "PLAN-2", timeline(101L, 20L));

        RobotState staleProjection = movingState(101L, 10L, 20L);

        SimulationPlaybackService.ActivationStateMismatch mismatch =
                SimulationPlaybackService.findActivationStateMismatch(
                        oldContext,
                        nextContext,
                        Map.of(101L, staleProjection),
                        Map.of(101L, 10L)
                );

        assertThat(mismatch).isNull();
    }

    @Test
    void newlyAddedRobotCanStartFromRegisteredNodeWithoutRedisProjection() {
        AiPlaybackContext oldContext = context(
                "PLAN-1", timeline(101L, 20L));
        oldContext.requestQuiesce();
        AiPlaybackContext nextContext = context(
                "PLAN-2",
                timeline(101L, 20L),
                timeline(102L, 30L));

        SimulationPlaybackService.ActivationStateMismatch mismatch =
                SimulationPlaybackService.findActivationStateMismatch(
                        oldContext,
                        nextContext,
                        Map.of(),
                        Map.of(101L, 10L, 102L, 30L)
                );

        assertThat(mismatch).isNull();
    }

    @Test
    void reportsRobotAndNodesWhenNewRobotStartCannotBeVerified() {
        AiPlaybackContext oldContext = context(
                "PLAN-1", timeline(101L, 20L));
        oldContext.requestQuiesce();
        AiPlaybackContext nextContext = context(
                "PLAN-2",
                timeline(101L, 20L),
                timeline(102L, 31L));

        SimulationPlaybackService.ActivationStateMismatch mismatch =
                SimulationPlaybackService.findActivationStateMismatch(
                        oldContext,
                        nextContext,
                        Map.of(),
                        Map.of(101L, 10L, 102L, 30L)
                );

        assertThat(mismatch).isNotNull();
        assertThat(mismatch.robotId()).isEqualTo(102L);
        assertThat(mismatch.reason()).isEqualTo("NEW_ROBOT_STATE_MISSING");
        assertThat(mismatch.expectedNodeId()).isEqualTo(31L);
        assertThat(mismatch.runtimeNodeId()).isEqualTo(30L);
    }

    @Test
    void repeatedActivationFailurePausesRunAndKeepsOldPlanHeld() {
        SimulationRunRepository runRepository = mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore = mock(SimulationRunStateStore.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LaroInventoryReservationService reservations =
                mock(LaroInventoryReservationService.class);
        SimulationRun run = mock(SimulationRun.class);
        Warehouse warehouse = mock(Warehouse.class);
        when(run.getStatus()).thenReturn(SimulationRunStatus.PENDING_ACTIVATION);
        when(run.getWarehouse()).thenReturn(warehouse);
        when(warehouse.getId()).thenReturn(2L);
        when(runRepository.findById(1L)).thenReturn(java.util.Optional.of(run));
        when(stateStore.findAll(1L)).thenReturn(List.of());

        SimulationPlaybackService service = new SimulationPlaybackService(
                runRepository,
                mock(SimulationRunRobotRepository.class),
                stateStore,
                mock(TaskRepository.class),
                mock(RobotRepository.class),
                mock(ChargingStationRepository.class),
                mock(WarehouseNodeRepository.class),
                mock(TaskService.class),
                mock(WarehousePathFinder.class),
                messagingTemplate,
                jdbcTemplate,
                reservations
        );
        AiPlaybackContext oldContext = context(
                "PLAN-1", timeline(101L, 20L));
        oldContext.requestQuiesce();
        AiPlaybackContext invalidNext = context(
                "PLAN-2", timeline(101L, 21L));
        installActiveContext(service, oldContext);
        pendingPlans(service).put(
                1L,
                new SimulationPlaybackService.PendingAiPlan(
                        invalidNext, Map.of(), Map.of(101L, 20L), 0)
        );

        service.tick(100L);
        service.tick(100L);

        assertThat(pendingPlans(service).get(1L).activationAttempts()).isEqualTo(2);
        assertThat(oldContext.isQuiescing()).isTrue();
        assertThat(oldContext.getRobots().get(0).isHeld()).isTrue();
        verify(run, never()).finishReplanning();

        service.tick(100L);

        assertThat(pendingPlans(service)).doesNotContainKey(1L);
        assertThat(suspendedRuns(service)).contains(1L);
        assertThat(oldContext.isQuiescing()).isTrue();
        assertThat(oldContext.getRobots().get(0).isHeld()).isTrue();
        verify(run).pauseForHumanReview(any(LocalDateTime.class));
        verify(run, never()).finishReplanning();
        verify(reservations).releaseActiveForPlan(1L, "PLAN-2");
    }

    private AiPlaybackContext context(
            String planId,
            AiPlaybackContext.RobotTimeline... robots
    ) {
        return new AiPlaybackContext(
                1L,
                2L,
                "WH-002",
                planId,
                "PLAN-1".equals(planId) ? 1 : 2,
                "BE-RUN-1",
                1_000L,
                1_000L,
                List.of(robots),
                Set.of(),
                1.0
        );
    }

    private AiPlaybackContext.RobotTimeline timeline(Long robotId, Long nodeId) {
        return new AiPlaybackContext.RobotTimeline(
                robotId,
                List.of(),
                nodeId,
                20
        );
    }

    private RobotState movingState(Long robotId, Long fromNodeId, Long toNodeId) {
        return new RobotState(
                robotId,
                2L,
                fromNodeId,
                "N" + fromNodeId,
                toNodeId,
                "N" + toNodeId,
                1.0,
                "MOVE-1",
                0L,
                1_000L,
                1_000L,
                1.0,
                20,
                RobotStatus.MOVING,
                null,
                null,
                RobotStatus.MOVING,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now()
        );
    }

    @SuppressWarnings("unchecked")
    private void installActiveContext(
            SimulationPlaybackService service,
            AiPlaybackContext context
    ) {
        Map<Long, AiPlaybackContext> contexts =
                (Map<Long, AiPlaybackContext>) ReflectionTestUtils.getField(
                        service, "aiContexts");
        contexts.put(context.getSimulationRunId(), context);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SimulationPlaybackService.PendingAiPlan> pendingPlans(
            SimulationPlaybackService service
    ) {
        return (Map<Long, SimulationPlaybackService.PendingAiPlan>)
                ReflectionTestUtils.getField(service, "pendingAiPlans");
    }

    @SuppressWarnings("unchecked")
    private Set<Long> suspendedRuns(SimulationPlaybackService service) {
        return (Set<Long>) ReflectionTestUtils.getField(service, "suspendedAiRunIds");
    }
}
