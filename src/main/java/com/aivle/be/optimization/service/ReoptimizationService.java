package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationCompletedEvent;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.optimization.validation.ReoptimizationPlanContractValidator;
import com.aivle.be.optimization.validation.ReoptimizationPlanStalenessValidator;
import com.aivle.be.optimization.validation.ReoptimizationPlanValidationException;
import com.aivle.be.optimization.validation.ReoptimizationPlanValidationInput;
import com.aivle.be.optimization.validation.ReoptimizationPlanValidator;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.ReplanningSnapshot;
import com.aivle.be.simulationrun.playback.RobotRuntime;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReoptimizationService {

    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private static final String REOPTIMIZATION_TOPIC_FORMAT =
            "/topic/simulation-runs/%d/reoptimization";

    private static final long REPLANNING_STOP_TIMEOUT_MILLIS = 10_000L;
    private static final long REPLANNING_STOP_POLL_MILLIS = 50L;

    private final SimulationRunRepository simulationRunRepository;
    private final TaskRepository taskRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final OptimizationClient optimizationClient;
    private final ReoptimizationPlanStagingService planStagingService;
    private final ReoptimizationPlanApplicationService planApplicationService;
    private final SimulationPlaybackService simulationPlaybackService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 같은 simulationRunId의 재계획 coordinator가 동시에 실행되지 않도록 한다.
     * 서로 다른 실행 ID는 서로 다른 key를 사용하므로 병렬 진행할 수 있다.
     *
     * 이 guard는 현재 재생 context와 마찬가지로 단일 애플리케이션 인스턴스를
     * 전제로 한다. 다중 인스턴스 분산 락은 Phase 2에서 다룬다.
     */
    private final ConcurrentMap<Long, Object> activeReoptimizations =
            new ConcurrentHashMap<>();

    /**
     * 외부 호출자가 트랜잭션 안에 있어도 coordinator 전체에서는 이를 suspend한다.
     * 필요한 DB 작업만 TransactionTemplate의 짧은 트랜잭션으로 실행한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReoptimizationResponse reoptimize(
            Long simulationRunId,
            ReoptimizationRequest request
    ) {
        Object flightToken = new Object();
        Object existingToken = activeReoptimizations.putIfAbsent(
                simulationRunId,
                flightToken
        );

        if (existingToken != null) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_ALREADY_IN_PROGRESS
            );
        }

        try {
            return reoptimizeSingleFlight(simulationRunId, request);
        } finally {
            activeReoptimizations.remove(simulationRunId, flightToken);
        }
    }

    private ReoptimizationResponse reoptimizeSingleFlight(
            Long simulationRunId,
            ReoptimizationRequest request
    ) {
        validateRunningSimulation(simulationRunId);

        if (!simulationPlaybackService.isPlaying(simulationRunId)) {
            throw new BusinessException(
                    ErrorCode.SIMULATION_RUN_NOT_RUNNING
            );
        }

        updateFailedRobotState(simulationRunId, request);

        if (!simulationPlaybackService
                .requestReplanningStop(simulationRunId)) {
            throw new BusinessException(
                    ErrorCode.SIMULATION_RUN_NOT_RUNNING
            );
        }

        awaitRobotsStoppedForReplanning(simulationRunId);

        ReplanningSnapshot runtimeSnapshot =
                simulationPlaybackService.captureReplanningSnapshot(
                        simulationRunId
                );

        if (runtimeSnapshot == null) {
            throw new BusinessException(
                    ErrorCode.SIMULATION_RUN_NOT_RUNNING
            );
        }

        // AI 오류 또는 응답 계약 거부가 발생해도 이 커밋은 유지되어야 한다.
        startReplanningInDatabase(simulationRunId);

        String replanId = UUID.randomUUID().toString();
        if (!simulationPlaybackService.bindReplanId(
                simulationRunId,
                runtimeSnapshot.snapshotVersion(),
                replanId
        )) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE
            );
        }

        ReoptimizationOptimizationRequest fastApiRequest =
                createFastApiRequest(
                        replanId,
                        simulationRunId,
                        runtimeSnapshot,
                        request
                );

        assertNoActiveTransactionForAiCall();

        ReoptimizationResponse response =
                optimizationClient.reoptimize(fastApiRequest);

        validateResponseCorrelation(fastApiRequest, response);
        validatePlanContract(fastApiRequest, response);
        ReplanningSnapshot currentSnapshot =
                captureCurrentSnapshotForValidation(simulationRunId);
        validateCompletePlan(
                fastApiRequest,
                response,
                currentSnapshot
        );
        ReplanningSnapshot stagingSnapshot =
                captureCurrentSnapshotForValidation(simulationRunId);
        validateSnapshotIsCurrent(
                fastApiRequest,
                response,
                runtimeSnapshot,
                stagingSnapshot
        );
        stageAndApplyValidatedPlan(
                fastApiRequest,
                response,
                runtimeSnapshot
        );
        throw rejectionUntilRuntimeActivationIsImplemented(response);
    }

    private void stageAndApplyValidatedPlan(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response,
            ReplanningSnapshot requestedSnapshot
    ) {
        if (response.status()
                != ReoptimizationResponse.Status.SUCCEEDED) {
            return;
        }

        ReoptimizationPlanStageCommand command =
                ReoptimizationPlanStageCommand.from(
                        request,
                        response
                );
        planStagingService.stage(command);

        ReplanningSnapshot applicationSnapshot =
                captureCurrentSnapshotForValidation(
                        request.simulationRunId()
                );
        validateSnapshotIsCurrent(
                request,
                response,
                requestedSnapshot,
                applicationSnapshot
        );
        ReoptimizationActivationPlan applied = planApplicationService.apply(
                request.simulationRunId(),
                request.replanId(),
                request.snapshotVersion()
        );
        if (applied.status()
                != ReoptimizationPlanStage.Status.DB_APPLIED) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_APPLY_FAILED
            );
        }
        simulationPlaybackService.installReoptimizationPlan(
                request.simulationRunId(),
                applied
        );
    }

    private void validateRunningSimulation(Long simulationRunId) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            SimulationRun simulationRun = findSimulationRun(simulationRunId);

            if (simulationRun.getStatus()
                    != SimulationRunStatus.RUNNING) {
                throw new BusinessException(
                        ErrorCode.SIMULATION_RUN_NOT_RUNNING
                );
            }
        });
    }

    private void startReplanningInDatabase(Long simulationRunId) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            SimulationRun simulationRun = findSimulationRun(simulationRunId);

            if (simulationRun.getStatus()
                    != SimulationRunStatus.RUNNING) {
                throw new BusinessException(
                        ErrorCode.SIMULATION_RUN_NOT_RUNNING
                );
            }

            simulationRun.startReplanning();
        });
    }

    private ReoptimizationOptimizationRequest createFastApiRequest(
            String replanId,
            Long simulationRunId,
            ReplanningSnapshot runtimeSnapshot,
            ReoptimizationRequest request
    ) {
        ReoptimizationOptimizationRequest fastApiRequest =
                transactionTemplate.execute(transactionStatus -> {
                    SimulationRun simulationRun =
                            findSimulationRun(simulationRunId);

                    if (simulationRun.getStatus()
                            != SimulationRunStatus.REPLANNING) {
                        throw new BusinessException(
                                ErrorCode.INVALID_SIMULATION_RUN_TRANSITION
                        );
                    }

                    List<Task> remainingTasks =
                            taskRepository
                                    .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                                            simulationRunId,
                                            ACTIVE_TASK_STATUSES
                                    );

                    List<ReoptimizationOptimizationRequest.RobotStateInput>
                            robots = runtimeSnapshot.robots().stream()
                            .map(robot ->
                                    new ReoptimizationOptimizationRequest.RobotStateInput(
                                            robot.robotId(),
                                            robot.currentNodeId(),
                                            robot.batteryLevel(),
                                            robot.status().name(),
                                            robot.currentTaskId(),
                                            robot.runtimePhase().name(),
                                            toRemainingStage(
                                                    runtimeSnapshot
                                                            .simulationClockMillis(),
                                                    robot
                                            )
                                    )
                            )
                            .toList();

                    List<ReoptimizationOptimizationRequest.TaskInput>
                            tasks = remainingTasks.stream()
                            .map(task ->
                                    new ReoptimizationOptimizationRequest.TaskInput(
                                            task.getId(),
                                            task.getRobot() == null
                                                    ? null
                                                    : task.getRobot().getId(),
                                            task.getStartNode().getId(),
                                            task.getEndNode().getId(),
                                            task.getTaskType().name(),
                                            task.getStatus().name()
                                    )
                            )
                            .toList();

                    return new ReoptimizationOptimizationRequest(
                            replanId,
                            simulationRunId,
                            runtimeSnapshot.snapshotVersion(),
                            runtimeSnapshot.simulationClockMillis(),
                            simulationRun.getWarehouse().getId(),
                            request.reason(),
                            request.triggerRobotId(),
                            request.blockedEdgeIds(),
                            request.description(),
                            robots,
                            tasks
                    );
                });

        if (fastApiRequest == null) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_SERVER_ERROR
            );
        }

        return fastApiRequest;
    }

    private ReoptimizationOptimizationRequest.RemainingStage
    toRemainingStage(
            Long simulationClockMillis,
            ReplanningSnapshot.RobotSnapshot robot
    ) {
        return switch (robot.runtimePhase()) {
            case MOVING_TO_START ->
                    ReoptimizationOptimizationRequest.RemainingStage.TO_START;
            case PICKING -> simulationClockMillis
                    >= robot.busyUntilMillis()
                    ? ReoptimizationOptimizationRequest.RemainingStage.TO_END
                    : ReoptimizationOptimizationRequest.RemainingStage.PICKING;
            case MOVING_TO_END ->
                    ReoptimizationOptimizationRequest.RemainingStage.TO_END;
            case DROPPING ->
                    ReoptimizationOptimizationRequest.RemainingStage.DROPPING;
            case IDLE, CHARGING ->
                    ReoptimizationOptimizationRequest.RemainingStage.IDLE;
        };
    }

    private void validateResponseCorrelation(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response
    ) {
        boolean matches = response != null
                && Objects.equals(
                request.replanId(),
                response.replanId()
        )
                && Objects.equals(
                request.simulationRunId(),
                response.simulationRunId()
        )
                && Objects.equals(
                request.snapshotVersion(),
                response.snapshotVersion()
        );

        if (!matches) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_RESPONSE_CORRELATION_MISMATCH
            );
        }
    }

    private BusinessException rejectionUntilRuntimeActivationIsImplemented(
            ReoptimizationResponse response
    ) {
        if (response.status() == null
                || response.status()
                == ReoptimizationResponse.Status.FAILED) {
            return new BusinessException(
                    ErrorCode.REOPTIMIZATION_AI_FAILED
            );
        }

        if (response.status()
                == ReoptimizationResponse.Status.INFEASIBLE) {
            return new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_INFEASIBLE
            );
        }

        /*
         * Phase 2-4A installs the immutable AI plan beside the legacy
         * execution fields. Activation, ready queues, and resume remain
         * unchanged until Phase 2-4B.
         */
        return new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_RUNTIME_ACTIVATION_NOT_IMPLEMENTED
        );
    }

    private void validatePlanContract(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response
    ) {
        try {
            ReoptimizationPlanContractValidator.validate(
                    request,
                    response
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_CONTRACT_INVALID,
                    exception
            );
        }
    }

    private ReplanningSnapshot captureCurrentSnapshotForValidation(
            Long simulationRunId
    ) {
        try {
            ReplanningSnapshot snapshot =
                    simulationPlaybackService.captureReplanningSnapshot(
                            simulationRunId
                    );

            if (snapshot == null) {
                throw new IllegalStateException(
                        "Replanning context is missing"
                );
            }
            return snapshot;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE,
                    exception
            );
        }
    }

    private void validateSnapshotIsCurrent(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response,
            ReplanningSnapshot requestedSnapshot,
            ReplanningSnapshot currentSnapshot
    ) {
        try {
            ReoptimizationPlanStalenessValidator.validate(
                    request,
                    response,
                    requestedSnapshot,
                    currentSnapshot
            );
        } catch (ReoptimizationPlanValidationException exception) {
            throw new BusinessException(
                    exception.getErrorCode(),
                    exception
            );
        }
    }

    private void validateCompletePlan(
            ReoptimizationOptimizationRequest request,
            ReoptimizationResponse response,
            ReplanningSnapshot currentSnapshot
    ) {
        ReoptimizationPlanValidationInput input =
                transactionTemplate.execute(transactionStatus -> {
                    Set<Long> validNodeIds = warehouseNodeRepository
                            .findAllByWarehouse_Id(request.warehouseId())
                            .stream()
                            .map(node -> node.getId())
                            .collect(Collectors.toSet());
                    List<WarehouseEdge> warehouseEdges =
                            warehouseEdgeRepository
                                    .findAllByFromNode_Warehouse_Id(
                                            request.warehouseId()
                                    );
                    List<ReoptimizationPlanValidationInput.DirectedEdge>
                            directedEdges = warehouseEdges.stream()
                            .flatMap(edge -> toDirectedEdges(edge).stream())
                            .toList();
                    Set<Long> warehouseEdgeIds = warehouseEdges.stream()
                            .map(WarehouseEdge::getId)
                            .collect(Collectors.toSet());
                    Set<Long> participantRobotIds = currentSnapshot
                            .robots().stream()
                            .map(ReplanningSnapshot.RobotSnapshot::robotId)
                            .collect(Collectors.toSet());
                    Set<Long> unavailableRobotIds = currentSnapshot
                            .robots().stream()
                            .filter(robot ->
                                    robot.status() == RobotStatus.ERROR
                                            || robot.status()
                                            == RobotStatus.OFFLINE
                            )
                            .map(ReplanningSnapshot.RobotSnapshot::robotId)
                            .collect(Collectors.toSet());

                    return new ReoptimizationPlanValidationInput(
                            currentSnapshot,
                            request,
                            response,
                            validNodeIds,
                            directedEdges,
                            warehouseEdgeIds,
                            participantRobotIds,
                            unavailableRobotIds
                    );
                });

        if (input == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            ReoptimizationPlanValidator.validate(input);
        } catch (ReoptimizationPlanValidationException exception) {
            throw new BusinessException(
                    exception.getErrorCode(),
                    exception
            );
        }
    }

    private List<ReoptimizationPlanValidationInput.DirectedEdge>
    toDirectedEdges(WarehouseEdge edge) {
        Long fromNodeId = edge.getFromNode().getId();
        Long toNodeId = edge.getToNode().getId();
        ReoptimizationPlanValidationInput.DirectedEdge forward =
                new ReoptimizationPlanValidationInput.DirectedEdge(
                        edge.getId(),
                        fromNodeId,
                        toNodeId
                );
        ReoptimizationPlanValidationInput.DirectedEdge reverse =
                new ReoptimizationPlanValidationInput.DirectedEdge(
                        edge.getId(),
                        toNodeId,
                        fromNodeId
                );

        return switch (edge.getDirectionType()) {
            case BOTH -> List.of(forward, reverse);
            case A_TO_B -> List.of(forward);
            case B_TO_A -> List.of(reverse);
        };
    }

    private SimulationRun findSimulationRun(Long simulationRunId) {
        return simulationRunRepository
                .findById(simulationRunId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.SIMULATION_RUN_NOT_FOUND
                        )
                );
    }

    private void assertNoActiveTransactionForAiCall() {
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "AI 재계획 호출 시 DB 트랜잭션이 활성 상태입니다."
            );
        }
    }

    /**
     * 모든 정상 로봇이 재계획을 위한 안전 정지를 완료할 때까지 기다린다.
     */
    private void awaitRobotsStoppedForReplanning(Long simulationRunId) {
        long deadlineNanos = System.nanoTime()
                + REPLANNING_STOP_TIMEOUT_MILLIS * 1_000_000L;

        while (!simulationPlaybackService
                .areAllRobotsStoppedForReplanning(simulationRunId)) {

            if (System.nanoTime() >= deadlineNanos) {
                throw new BusinessException(
                        ErrorCode.REPLANNING_STOP_TIMEOUT
                );
            }

            try {
                Thread.sleep(REPLANNING_STOP_POLL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();

                throw new BusinessException(
                        ErrorCode.REPLANNING_STOP_TIMEOUT,
                        exception
                );
            }
        }
    }

    private void updateFailedRobotState(
            Long simulationRunId,
            ReoptimizationRequest request
    ) {
        if (request.reason() != ReoptimizationReason.ROBOT_FAILURE
                || request.triggerRobotId() == null) {
            return;
        }

        boolean updated =
                simulationPlaybackService.markRobotError(
                        simulationRunId,
                        request.triggerRobotId()
                );

        if (!updated) {
            throw new BusinessException(
                    ErrorCode.ROBOT_NOT_IN_SIMULATION_RUN
            );
        }
    }

    /**
     * Phase 2에서 실행 계획 적용이 구현되면 DB 커밋 이후에만 호출한다.
     * 현재 Phase 1에서는 성공 적용 경로가 없으므로 호출되지 않는다.
     */
    @SuppressWarnings("unused")
    private void publishReoptimizationCompletedAfterCommit(
            Long simulationRunId,
            Long optimizationResultId,
            ReoptimizationRequest request,
            ReoptimizationResponse response,
            List<Long> changedTaskIds,
            List<Long> affectedRobotIds
    ) {
        ReoptimizationCompletedEvent event =
                ReoptimizationCompletedEvent.of(
                        simulationRunId,
                        optimizationResultId,
                        response,
                        request.reason(),
                        request.triggerRobotId(),
                        changedTaskIds,
                        affectedRobotIds
                );

        String topic = REOPTIMIZATION_TOPIC_FORMAT.formatted(
                simulationRunId
        );

        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            messagingTemplate.convertAndSend(
                                    topic,
                                    event
                            );
                        }
                    }
            );
            return;
        }

        messagingTemplate.convertAndSend(topic, event);
    }
}
