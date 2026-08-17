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
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimulationPlaybackServiceAiPlaybackTest {

    @Test
    void injectedLowBatteryUpdatesPlaybackSourceAndQueuesReplanAtSafeBoundary() {
        Fixture fixture = fixture();
        AiPlaybackContext context = movingContext(1L, 101L, 10L, 20L);
        AiPlaybackContext.RobotTimeline robot = context.getRobots().get(0);
        robot.setCurrentTaskId(301L);
        robot.setStatus(RobotStatus.MOVING);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "RJ01"));

        SimulationPlaybackService.LowBatteryInjection result =
                fixture.service().injectRandomActiveRobotLowBattery(1L, 20);

        assertThat(result.robotId()).isEqualTo(101L);
        assertThat(result.previousBatteryLevel()).isEqualTo(100);
        assertThat(result.batteryLevel()).isEqualTo(20);
        assertThat(robot.getBatteryLevel()).isEqualTo(20);

        fixture.service().tick(100L);

        assertThat(fixture.service().pendingLowBatteryReplanRequests())
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.simulationRunId()).isEqualTo(1L);
                    assertThat(request.robotId()).isEqualTo(101L);
                    assertThat(request.batteryLevel()).isEqualTo(20);
                    assertThat(request.chargingThreshold()).isEqualTo(20);
                    assertThat(request.currentNodeId()).isEqualTo(10L);
                    assertThat(request.currentNodeCode()).isEqualTo("C01");
                    assertThat(request.currentTaskId()).isEqualTo(301L);
                    assertThat(request.carryingLoad()).isFalse();
                    assertThat(request.stoppedAtSimTimeMs()).isEqualTo(100L);
                });
        assertThat(robot.isLowBatteryHold()).isTrue();

        ArgumentCaptor<RobotState> stateCaptor = ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore(), times(2)).save(eq(1L), stateCaptor.capture());
        assertThat(stateCaptor.getAllValues())
                .allSatisfy(state -> assertThat(state.batteryLevel()).isEqualTo(20));
    }

    @Test
    void injectedLowBatterySupportsZeroPercentChargingThreshold() {
        Fixture fixture = fixture();
        AiPlaybackContext context = movingContext(1L, 101L, 10L, 20L);
        AiPlaybackContext.RobotTimeline robot = context.getRobots().get(0);
        robot.setCurrentTaskId(301L);
        robot.setStatus(RobotStatus.MOVING);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "RJ01"));

        SimulationPlaybackService.LowBatteryInjection result =
                fixture.service().injectRandomActiveRobotLowBattery(1L, 0);

        assertThat(result.batteryLevel()).isZero();
        assertThat(robot.getBatteryLevel()).isZero();
    }

    @Test
    void injectedLowBatteryDoesNotQueueAnotherReplanWhenRobotAlreadyReturnsToCharge() {
        Fixture fixture = fixture();
        AiPlaybackContext context = movingThenChargingContext(1L, 101L, 10L, 20L);
        AiPlaybackContext.RobotTimeline robot = context.getRobots().get(0);
        robot.setCurrentTaskId(301L);
        robot.setStatus(RobotStatus.MOVING);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "RJ01"));

        SimulationPlaybackService.LowBatteryInjection result =
                fixture.service().injectRandomActiveRobotLowBattery(1L, 20);

        assertThat(result.robotId()).isEqualTo(101L);
        fixture.service().tick(100L);
        assertThat(fixture.service().pendingLowBatteryReplanRequests()).isEmpty();
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.MOVING);
    }

    @Test
    void lowBatteryRecoveryRouteMovesToChargerAndIncreasesBattery() {
        Fixture fixture = fixture();
        AiPlaybackContext context = lowBatteryRecoveryContext(1L, 101L, 10L, 20L);
        AiPlaybackContext.RobotTimeline robot = context.getRobots().get(0);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "CH01"));

        fixture.service().tick(100L);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.MOVING);
        assertThat(fixture.service().pendingLowBatteryReplanRequests()).isEmpty();

        ArgumentCaptor<RobotState> returningStateCaptor =
                ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore()).save(eq(1L), returningStateCaptor.capture());
        assertThat(returningStateCaptor.getValue().activity())
                .isEqualTo(RobotStatus.RETURNING_TO_CHARGE);

        fixture.service().tick(900L);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.CHARGING);
        assertThat(robot.getCurrentNodeId()).isEqualTo(20L);
        assertThat(robot.getBatteryLevel()).isEqualTo(19);

        fixture.service().tick(1_000L);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.CHARGING);
        assertThat(robot.getBatteryLevel()).isEqualTo(20);
        assertThat(fixture.service().pendingLowBatteryReplanRequests()).isEmpty();
    }

    @Test
    void ordinaryMovePublishesNullWaitingTimes() {
        Fixture fixture = fixture();
        installContext(fixture.service(), movingContext(1L, 101L, 10L, 20L));
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "RJ01"));

        fixture.service().tick(100L);

        ArgumentCaptor<RobotState> stateCaptor = ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore()).save(eq(1L), stateCaptor.capture());
        RobotState state = stateCaptor.getValue();
        assertThat(state.status()).isEqualTo(RobotStatus.MOVING);
        assertThat(state.currentNodeId()).isEqualTo(10L);
        assertThat(state.nextNodeId()).isEqualTo(20L);
        assertThat(state.movementProgress()).isEqualTo(0.1);
        assertThat(state.waitingReason()).isNull();
        assertThat(state.waitStartedAtMillis()).isNull();
        assertThat(state.estimatedResumeAtMillis()).isNull();
    }

    @Test
    void failedRunIsSuspendedWithoutBlockingOtherRunsOrRepeatingTheFailure() {
        Fixture fixture = fixture();
        installContext(fixture.service(), movingContext(1L, 101L, 10L, 20L));
        installContext(fixture.service(), movingContext(2L, 201L, 30L, 40L));
        cacheNodeCodes(fixture.service(), Map.of(
                10L, "C01",
                20L, "RJ01",
                30L, "C02",
                40L, "RJ02"
        ));
        when(fixture.stateStore().save(eq(1L), any(RobotState.class)))
                .thenThrow(new IllegalStateException("state publication failed"));

        fixture.service().tick(100L);
        fixture.service().tick(100L);

        verify(fixture.stateStore(), times(1)).save(eq(1L), any(RobotState.class));
        verify(fixture.stateStore(), times(2)).save(eq(2L), any(RobotState.class));
        assertThat(suspendedRunIds(fixture.service())).containsExactly(1L);
    }

    private Fixture fixture() {
        SimulationRunRepository runRepository = mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore = mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationRun run = mock(SimulationRun.class);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(runRepository.findById(2L)).thenReturn(Optional.of(run));
        when(taskRepository.findById(-1L)).thenReturn(Optional.empty());

        SimulationPlaybackService service = new SimulationPlaybackService(
                runRepository,
                mock(SimulationRunRobotRepository.class),
                stateStore,
                taskRepository,
                mock(RobotRepository.class),
                mock(ChargingStationRepository.class),
                mock(WarehouseNodeRepository.class),
                mock(TaskService.class),
                mock(WarehousePathFinder.class),
                mock(SimpMessagingTemplate.class),
                mock(JdbcTemplate.class),
                mock(LaroInventoryReservationService.class)
        );
        return new Fixture(service, stateStore);
    }

    private AiPlaybackContext movingContext(
            Long runId,
            Long robotId,
            Long fromNodeId,
            Long toNodeId
    ) {
        AiPlaybackContext.TimedStep move = new AiPlaybackContext.TimedStep(
                "MOVE-" + robotId,
                0,
                AiPlaybackContext.StepType.MOVE,
                0L,
                1_000L,
                null,
                fromNodeId,
                toNodeId,
                null,
                null,
                null
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                robotId,
                List.of(move),
                fromNodeId,
                100
        );
        return new AiPlaybackContext(
                runId,
                2L,
                "WH-002",
                "PLAN-" + runId,
                1,
                "BE-RUN-" + runId,
                0L,
                1_000L,
                List.of(robot),
                Set.of(),
                1.0
        );
    }

    private AiPlaybackContext movingThenChargingContext(
            Long runId,
            Long robotId,
            Long fromNodeId,
            Long toNodeId
    ) {
        AiPlaybackContext.TimedStep move = new AiPlaybackContext.TimedStep(
                "MOVE-" + robotId,
                0,
                AiPlaybackContext.StepType.MOVE,
                0L,
                1_000L,
                null,
                fromNodeId,
                toNodeId,
                301L,
                null,
                null
        );
        AiPlaybackContext.TimedStep charge = new AiPlaybackContext.TimedStep(
                "CHARGE-" + robotId,
                1,
                AiPlaybackContext.StepType.SERVICE,
                1_000L,
                61_000L,
                toNodeId,
                null,
                null,
                null,
                "CHARGE"
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                robotId,
                List.of(move, charge),
                fromNodeId,
                100
        );
        return new AiPlaybackContext(
                runId,
                2L,
                "WH-002",
                "PLAN-" + runId,
                1,
                "BE-RUN-" + runId,
                0L,
                61_000L,
                List.of(robot),
                Set.of(),
                1.0
        );
    }

    private AiPlaybackContext lowBatteryRecoveryContext(
            Long runId,
            Long robotId,
            Long fromNodeId,
            Long chargingNodeId
    ) {
        AiPlaybackContext.TimedStep move = new AiPlaybackContext.TimedStep(
                "MOVE-TO-CHARGE-" + robotId,
                0,
                AiPlaybackContext.StepType.MOVE,
                0L,
                1_000L,
                null,
                fromNodeId,
                chargingNodeId,
                null,
                null
        );
        AiPlaybackContext.TimedStep charge = new AiPlaybackContext.TimedStep(
                "CHARGE-" + robotId,
                1,
                AiPlaybackContext.StepType.SERVICE,
                1_000L,
                61_000L,
                chargingNodeId,
                null,
                null,
                null,
                "CHARGE"
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                robotId,
                List.of(move, charge),
                fromNodeId,
                20
        );
        return new AiPlaybackContext(
                runId,
                2L,
                "WH-002",
                "PLAN-LOW-BATTERY-" + runId,
                2,
                "BE-RUN-" + runId,
                0L,
                61_000L,
                List.of(robot),
                Set.of(),
                1.0,
                20,
                Map.of(chargingNodeId, 60.0)
        );
    }

    @SuppressWarnings("unchecked")
    private void installContext(SimulationPlaybackService service, AiPlaybackContext context) {
        Map<Long, AiPlaybackContext> contexts =
                (Map<Long, AiPlaybackContext>) ReflectionTestUtils.getField(service, "aiContexts");
        contexts.put(context.getSimulationRunId(), context);
    }

    @SuppressWarnings("unchecked")
    private void cacheNodeCodes(SimulationPlaybackService service, Map<Long, String> values) {
        Map<Long, String> nodeCodes =
                (Map<Long, String>) ReflectionTestUtils.getField(service, "nodeCodeCache");
        nodeCodes.putAll(values);
    }

    @SuppressWarnings("unchecked")
    private Set<Long> suspendedRunIds(SimulationPlaybackService service) {
        return (Set<Long>) ReflectionTestUtils.getField(service, "suspendedAiRunIds");
    }

    private record Fixture(
            SimulationPlaybackService service,
            SimulationRunStateStore stateStore
    ) {
    }
}
