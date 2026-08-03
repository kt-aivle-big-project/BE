package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationCompletedEvent;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final OptimizationClient optimizationClient;
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

        // AI 오류 또는 응답 계약 거부가 발생해도 이 커밋은 유지되어야 한다.
        startReplanningInDatabase(simulationRunId);

        ReoptimizationOptimizationRequest fastApiRequest =
                createFastApiRequest(simulationRunId, request);

        assertNoActiveTransactionForAiCall();

        ReoptimizationResponse response =
                optimizationClient.reoptimize(fastApiRequest);

        /*
         * Phase 1에서는 현재 응답 계약으로 task별 실행 계획을 만들 수 없다.
         * DB 작업 배정, Runtime task/path, ready queue, resume를 절대 변경하지 않는다.
         */
        throw new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_CONTRACT_INCOMPLETE
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
            Long simulationRunId,
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

                    List<RobotState> robotStates =
                            simulationRunStateStore.findAll(
                                    simulationRunId
                            );

                    List<Task> remainingTasks =
                            taskRepository
                                    .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                                            simulationRunId,
                                            ACTIVE_TASK_STATUSES
                                    );

                    List<ReoptimizationOptimizationRequest.RobotStateInput>
                            robots = robotStates.stream()
                            .map(state ->
                                    new ReoptimizationOptimizationRequest.RobotStateInput(
                                            state.robotId(),
                                            state.currentNodeId(),
                                            state.batteryLevel() == null
                                                    ? null
                                                    : state.batteryLevel().doubleValue(),
                                            state.status().name()
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
                            simulationRunId,
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
