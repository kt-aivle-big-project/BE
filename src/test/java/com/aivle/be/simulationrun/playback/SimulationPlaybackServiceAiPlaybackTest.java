package com.aivle.be.simulationrun.playback;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
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
        assertThat(robot.isLowBatteryHold()).isFalse();
        assertThat(robot.isHeld()).isFalse();
        assertThat(robot.hasLowBatteryAlert()).isTrue();

        ArgumentCaptor<RobotState> stateCaptor = ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore(), times(2)).save(eq(1L), stateCaptor.capture());
        assertThat(stateCaptor.getAllValues())
                .allSatisfy(state -> assertThat(state.batteryLevel()).isEqualTo(20));
    }

    @Test
    void lowBatteryBarrierFinishesCurrentTaskBeforeRobotBecomesReady() {
        Fixture fixture = fixture();
        AiPlaybackContext context = activeTaskContext(1L, 101L, 301L);
        AiPlaybackContext.RobotTimeline robot = context.getRobots().get(0);
        robot.setCurrentTaskId(301L);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(
                10L, "A00",
                11L, "R0_0",
                12L, "R0_1",
                13L, "A01"
        ));

        fixture.service().injectRandomActiveRobotLowBattery(1L, 20);
        fixture.service().tick(100L);
        fixture.service().beginQuiescing(1L);

        assertThat(fixture.service().isReadyForReplanRequest(1L)).isFalse();
        assertThat(robot.isHeld()).isFalse();

        fixture.service().tick(100L);
        fixture.service().tick(100L);
        fixture.service().tick(100L);

        assertThat(fixture.service().isReadyForReplanRequest(1L)).isTrue();
        assertThat(robot.isHeld()).isTrue();
        assertThat(robot.isLowBatteryHold()).isTrue();
        assertThat(robot.isCarryingLoad()).isFalse();
        assertThat(robot.getCurrentNodeId()).isEqualTo(12L);
        assertThat(robot.getHeldAtMillis()).isEqualTo(400L);
        verify(fixture.taskService()).applyInventoryAtServiceCompletion(301L, "PICKUP");
        verify(fixture.taskService()).applyInventoryAtServiceCompletion(301L, "DROP");

        ArgumentCaptor<RobotState> stateCaptor = ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore(), times(5)).save(eq(1L), stateCaptor.capture());
        List<RobotState> states = stateCaptor.getAllValues();
        RobotState heldState = states.get(states.size() - 1);
        assertThat(heldState.waitStartedAtMillis()).isEqualTo(400L);
        assertThat(heldState.waitingReason()).contains("배터리", "재계획");
    }

    @Test
    void barrierFinishesAnAlreadyStartedOutboundEgressMove() {
        Fixture fixture = fixture();
        Long taskId = 301L;
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                101L,
                List.of(
                        new AiPlaybackContext.TimedStep(
                                "STATION", 0, AiPlaybackContext.StepType.SERVICE,
                                0, 100, 20L, null, null, taskId, "STATION"
                        ),
                        new AiPlaybackContext.TimedStep(
                                "MOVE-EGRESS", 1, AiPlaybackContext.StepType.MOVE,
                                100, 300, null, 20L, 30L, taskId, null
                        ),
                        new AiPlaybackContext.TimedStep(
                                "NEXT-PICKUP", 2, AiPlaybackContext.StepType.SERVICE,
                                300, 400, 30L, null, null, 302L, "PICKUP"
                        )
                ),
                20L,
                100
        );
        AiPlaybackContext context = new AiPlaybackContext(
                1L, 2L, "WH-002", "PLAN-EGRESS-1", 1, "BE-RUN-1",
                0, 400, List.of(robot), Set.of(taskId, 302L), 1.0
        );
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(20L, "ST01", 30L, "A01"));

        // Inject during STATION. Its completion queues the low-battery request
        // at a safe step boundary, but the playback thread can enter the next
        fixture.service().tick(50L);
        fixture.service().injectRandomActiveRobotLowBattery(1L, 20);
        fixture.service().tick(50L);

        assertThat(fixture.service().pendingLowBatteryReplanRequests())
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.robotId()).isEqualTo(101L);
                    assertThat(request.currentTaskId()).isEqualTo(taskId);
                    assertThat(request.currentNodeId()).isEqualTo(20L);
                    assertThat(request.carryingLoad()).isFalse();
                    assertThat(request.stoppedAtSimTimeMs()).isEqualTo(100L);
                });
        assertThat(robot.currentStep().stepId()).isEqualTo("MOVE-EGRESS");
        assertThat(robot.isStepStarted()).isFalse();

        fixture.service().acknowledgeLowBatteryReplanRequest(1L, 101L);
        fixture.service().tick(50L);
        assertThat(robot.currentStep().stepId()).isEqualTo("MOVE-EGRESS");
        assertThat(robot.isStepStarted()).isTrue();

        fixture.service().beginQuiescing(1L);

        assertThat(fixture.service().replanBarrierStatus(1L))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.handoverAtMillis()).isEqualTo(300L);
                    assertThat(status.handoverNodeId()).isEqualTo(30L);
                });
        assertThat(fixture.service().isReadyForReplanRequest(1L)).isFalse();

        fixture.service().tick(150L);

        assertThat(fixture.service().isReadyForReplanRequest(1L)).isTrue();
        assertThat(robot.isHeld()).isTrue();
        assertThat(robot.getCurrentNodeId()).isEqualTo(30L);
        assertThat(robot.currentStep().stepId()).isEqualTo("NEXT-PICKUP");
        verify(fixture.taskService()).applyInventoryAtServiceCompletion(taskId, "STATION");
    }

    @Test
    void physicalTaskCompletionIsPersistedBeforeReplanCanReplaceOldPlan() {
        Fixture fixture = fixture();
        Task task = mock(Task.class);
        when(task.getStatus()).thenReturn(TaskStatus.IN_PROGRESS);
        when(fixture.taskRepository().findById(301L)).thenReturn(Optional.of(task));
        AiPlaybackContext context = activeTaskContext(1L, 101L, 301L);
        installContext(fixture.service(), context);

        fixture.service().tick(400L);

        verify(fixture.taskService()).completeTask(301L);
        assertThat(context.getRobots().get(0).getCurrentNodeId()).isEqualTo(12L);
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
        robot.markLowBatteryAlert();
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

        ArgumentCaptor<RobotState> chargingStateCaptor =
                ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore(), times(2)).save(eq(1L), chargingStateCaptor.capture());
        assertThat(chargingStateCaptor.getAllValues().get(1).activity())
                .isEqualTo(RobotStatus.CHARGING);

        fixture.service().tick(1_000L);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.CHARGING);
        assertThat(robot.getBatteryLevel()).isEqualTo(20);
        assertThat(fixture.service().pendingLowBatteryReplanRequests()).isEmpty();

        fixture.service().tick(80_000L);
        assertThat(robot.getBatteryLevel()).isEqualTo(100);
        assertThat(robot.getStatus()).isEqualTo(RobotStatus.IDLE);
        assertThat(robot.hasLowBatteryAlert()).isFalse();
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
    void alreadySafeRobotIsPublishedBeforeReplanRequestCanStart() {
        Fixture fixture = fixture();
        AiPlaybackContext context = movingContext(1L, 101L, 10L, 20L);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(10L, "C01", 20L, "RJ01"));

        fixture.service().beginQuiescing(1L);

        assertThat(fixture.service().isReadyForReplanRequest(1L)).isTrue();
        ArgumentCaptor<RobotState> stateCaptor = ArgumentCaptor.forClass(RobotState.class);
        verify(fixture.stateStore()).save(eq(1L), stateCaptor.capture());
        RobotState state = stateCaptor.getValue();
        assertThat(state.currentNodeId()).isEqualTo(10L);
        assertThat(state.status()).isEqualTo(RobotStatus.IDLE);
        assertThat(state.waitStartedAtMillis()).isZero();
        assertThat(state.waitingReason()).isEqualTo("재계획 안전 노드에서 대기 중");
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

    @Test
    void occupiedInboundDropFailsOnlyAffectedRobotAndOtherPlaybackContinues() {
        Fixture fixture = fixture();
        AiPlaybackContext context = inboundDropContext(1L, 101L, 20L, 3325L);
        installContext(fixture.service(), context);
        cacheNodeCodes(fixture.service(), Map.of(20L, "R2_2"));
        when(fixture.taskService().applyInventoryAtServiceCompletion(3325L, "DROP"))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        fixture.service().tick(1_000L);

        assertThat(suspendedRunIds(fixture.service())).isEmpty();
        AiPlaybackContext.RobotTimeline failedRobot = context.getRobots().stream()
                .filter(robot -> robot.getRobotId().equals(101L))
                .findFirst()
                .orElseThrow();
        AiPlaybackContext.RobotTimeline otherRobot = context.getRobots().stream()
                .filter(robot -> robot.getRobotId().equals(102L))
                .findFirst()
                .orElseThrow();
        assertThat(failedRobot.isFailed()).isTrue();
        assertThat(failedRobot.getStatus()).isEqualTo(RobotStatus.ERROR);
        assertThat(otherRobot.getStatus()).isEqualTo(RobotStatus.MOVING);

        fixture.service().tick(59_000L);

        assertThat(otherRobot.getCurrentNodeId()).isEqualTo(21L);
        verify(fixture.taskService(), times(1))
                .applyInventoryAtServiceCompletion(3325L, "DROP");
    }

    private Fixture fixture() {
        SimulationRunRepository runRepository = mock(SimulationRunRepository.class);
        SimulationRunStateStore stateStore = mock(SimulationRunStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        SimulationRun run = mock(SimulationRun.class);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(runRepository.findById(2L)).thenReturn(Optional.of(run));
        when(taskRepository.findById(any())).thenReturn(Optional.empty());

        TaskService taskService = mock(TaskService.class);
        SimulationPlaybackService service = new SimulationPlaybackService(
                runRepository,
                mock(SimulationRunRobotRepository.class),
                stateStore,
                taskRepository,
                mock(RobotRepository.class),
                mock(ChargingStationRepository.class),
                mock(WarehouseNodeRepository.class),
                taskService,
                mock(WarehousePathFinder.class),
                mock(SimpMessagingTemplate.class),
                mock(JdbcTemplate.class),
                mock(LaroInventoryReservationService.class)
        );
        return new Fixture(service, stateStore, taskRepository, taskService);
    }

    private AiPlaybackContext inboundDropContext(
            Long runId,
            Long robotId,
            Long rackNodeId,
            Long taskId
    ) {
        AiPlaybackContext.TimedStep drop = new AiPlaybackContext.TimedStep(
                "DROP-" + taskId,
                0,
                AiPlaybackContext.StepType.SERVICE,
                0L,
                1_000L,
                rackNodeId,
                null,
                null,
                taskId,
                "DROP"
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                robotId,
                List.of(drop),
                rackNodeId,
                100
        );
        AiPlaybackContext.TimedStep otherMove = new AiPlaybackContext.TimedStep(
                "MOVE-OTHER-" + robotId,
                0,
                AiPlaybackContext.StepType.MOVE,
                0L,
                60_000L,
                null,
                rackNodeId,
                rackNodeId + 1,
                taskId + 1,
                null
        );
        AiPlaybackContext.RobotTimeline otherRobot = new AiPlaybackContext.RobotTimeline(
                robotId + 1,
                List.of(otherMove),
                rackNodeId,
                100
        );
        return new AiPlaybackContext(
                runId,
                2L,
                "WH-002",
                "PLAN-INBOUND-DROP-" + runId,
                2,
                "BE-RUN-" + runId,
                0L,
                60_000L,
                List.of(otherRobot, robot),
                Set.of(),
                1.0
        );
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

    private AiPlaybackContext activeTaskContext(Long runId, Long robotId, Long taskId) {
        List<AiPlaybackContext.TimedStep> steps = List.of(
                new AiPlaybackContext.TimedStep(
                        "MOVE-PICKUP", 0, AiPlaybackContext.StepType.MOVE,
                        0, 100, null, 10L, 11L, taskId, null
                ),
                new AiPlaybackContext.TimedStep(
                        "PICKUP", 1, AiPlaybackContext.StepType.SERVICE,
                        100, 200, 11L, null, null, taskId, "PICKUP"
                ),
                new AiPlaybackContext.TimedStep(
                        "MOVE-DROP", 2, AiPlaybackContext.StepType.MOVE,
                        200, 300, null, 11L, 12L, taskId, null
                ),
                new AiPlaybackContext.TimedStep(
                        "DROP", 3, AiPlaybackContext.StepType.SERVICE,
                        300, 400, 12L, null, null, taskId, "DROP"
                ),
                new AiPlaybackContext.TimedStep(
                        "MOVE-EGRESS", 4, AiPlaybackContext.StepType.MOVE,
                        400, 500, null, 12L, 13L, null, null
                )
        );
        AiPlaybackContext.RobotTimeline robot = new AiPlaybackContext.RobotTimeline(
                robotId,
                steps,
                10L,
                100
        );
        return new AiPlaybackContext(
                runId,
                2L,
                "WH-002",
                "PLAN-TASK-" + runId,
                1,
                "BE-RUN-" + runId,
                0,
                500,
                List.of(robot),
                Set.of(taskId),
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
            SimulationRunStateStore stateStore,
            TaskRepository taskRepository,
            TaskService taskService
    ) {
    }
}
