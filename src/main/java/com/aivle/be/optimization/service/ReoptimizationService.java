package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationCompletedEvent;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.optimization.entity.OptimizationResult;
import com.aivle.be.optimization.entity.RobotRouteResult;
import com.aivle.be.optimization.entity.TaskAssignmentResult;
import com.aivle.be.optimization.repository.OptimizationResultRepository;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final OptimizationClient optimizationClient;
    private final OptimizationResultRepository optimizationResultRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ReplanningStateService replanningStateService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public ReoptimizationResponse reoptimize(
            Long simulationRunId,
            ReoptimizationRequest request
    ) {
        SimulationRun simulationRun = simulationRunRepository
                .findById(simulationRunId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.SIMULATION_RUN_NOT_FOUND
                        )
                );

        if (simulationRun.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(
                    ErrorCode.SIMULATION_RUN_NOT_RUNNING
            );
        }

        // 별도 트랜잭션으로 REPLANNING 상태를 즉시 반영한다.
        // SimulationPlaybackService.tick()은 RUNNING이 아니므로 진행을 멈춘다.
        replanningStateService.startReplanning(simulationRunId);

        // 현재 트랜잭션이 성공하거나 실패한 뒤 시뮬레이션을 다시 실행 상태로 복구한다.
        registerReplanningFinishAfterCompletion(simulationRunId);

        List<RobotState> robotStates =
                simulationRunStateStore.findAll(simulationRunId);

        List<Task> remainingTasks =
                taskRepository
                        .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                                simulationRunId,
                                ACTIVE_TASK_STATUSES
                        );

        List<ReoptimizationOptimizationRequest.RobotStateInput> robots =
                robotStates.stream()
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

        List<ReoptimizationOptimizationRequest.TaskInput> tasks =
                remainingTasks.stream()
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

        ReoptimizationOptimizationRequest fastApiRequest =
                new ReoptimizationOptimizationRequest(
                        simulationRunId,
                        simulationRun.getWarehouse().getId(),
                        request.reason(),
                        request.triggerRobotId(),
                        request.blockedEdgeIds(),
                        request.description(),
                        robots,
                        tasks
                );

        ReoptimizationResponse response =
                optimizationClient.reoptimize(fastApiRequest);

        List<TaskAssignmentResult> assignmentResults =
                applyAssignments(
                        simulationRun,
                        request,
                        response
                );

        OptimizationResult savedResult =
                saveReoptimizationResult(
                        simulationRun,
                        request,
                        response,
                        assignmentResults
                );

        publishReoptimizationCompletedAfterCommit(
                simulationRun,
                request,
                response,
                assignmentResults,
                savedResult
        );

        return response;
    }

    private void registerReplanningFinishAfterCompletion(
            Long simulationRunId
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            replanningStateService.finishReplanning(simulationRunId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        replanningStateService.finishReplanning(
                                simulationRunId
                        );
                    }
                }
        );
    }

    private List<TaskAssignmentResult> applyAssignments(
            SimulationRun simulationRun,
            ReoptimizationRequest request,
            ReoptimizationResponse response
    ) {
        List<TaskAssignmentResult> assignmentResults =
                new ArrayList<>();

        if (response.assignments() != null) {
            for (ReoptimizationResponse.TaskAssignment assignment
                    : response.assignments()) {

                Task task = taskRepository.findById(assignment.taskId())
                        .orElseThrow(() ->
                                new BusinessException(
                                        ErrorCode.TASK_NOT_FOUND
                                )
                        );

                Robot robot = robotRepository.findById(assignment.robotId())
                        .orElseThrow(() ->
                                new BusinessException(
                                        ErrorCode.ROBOT_NOT_FOUND
                                )
                        );

                if (task.getSimulationRun() == null
                        || !task.getSimulationRun().getId()
                        .equals(simulationRun.getId())) {
                    throw new BusinessException(
                            ErrorCode.TASK_NOT_FOUND
                    );
                }

                if (!robot.getWarehouse().getId()
                        .equals(simulationRun.getWarehouse().getId())) {
                    throw new BusinessException(
                            ErrorCode.ROBOT_NOT_FOUND
                    );
                }

                Long previousRobotId =
                        task.getRobot() == null
                                ? null
                                : task.getRobot().getId();

                if (task.getStatus() == TaskStatus.PENDING) {
                    task.assignRobot(robot);
                } else if (task.getStatus() == TaskStatus.ASSIGNED
                        || task.getStatus() == TaskStatus.IN_PROGRESS) {
                    task.reassignRobot(robot);
                }

                if (previousRobotId == null
                        || !previousRobotId.equals(robot.getId())) {

                    TaskAssignmentResult assignmentResult =
                            TaskAssignmentResult.create(
                                    task.getId(),
                                    previousRobotId,
                                    robot.getId()
                            );

                    assignmentResults.add(assignmentResult);
                }

                updateAssignedRobotState(
                        simulationRun.getId(),
                        task,
                        robot
                );
            }
        }

        updateFailedRobotState(
                simulationRun.getId(),
                request
        );

        return assignmentResults;
    }

    private OptimizationResult saveReoptimizationResult(
            SimulationRun simulationRun,
            ReoptimizationRequest request,
            ReoptimizationResponse response,
            List<TaskAssignmentResult> assignmentResults
    ) {
        OptimizationResult result =
                OptimizationResult.createReoptimization(
                        response.requestId(),
                        simulationRun.getWarehouse().getId(),
                        simulationRun.getId(),
                        response.status(),
                        request.reason(),
                        request.triggerRobotId(),
                        request.description()
                );

        if (response.routes() != null) {
            for (ReoptimizationResponse.RobotRoute route
                    : response.routes()) {

                RobotRouteResult routeResult =
                        RobotRouteResult.create(
                                route.robotId(),
                                convertNodePathToJson(route.nodePath()),
                                route.totalDistance(),
                                route.estimatedTime()
                        );

                result.addRoute(routeResult);
            }
        }

        for (TaskAssignmentResult assignmentResult
                : assignmentResults) {
            result.addTaskAssignment(assignmentResult);
        }

        return optimizationResultRepository.save(result);
    }

    private void publishReoptimizationCompletedAfterCommit(
            SimulationRun simulationRun,
            ReoptimizationRequest request,
            ReoptimizationResponse response,
            List<TaskAssignmentResult> assignmentResults,
            OptimizationResult savedResult
    ) {
        List<Long> changedTaskIds = assignmentResults.stream()
                .map(TaskAssignmentResult::getTaskId)
                .distinct()
                .toList();

        Set<Long> affectedRobotIdSet = new LinkedHashSet<>();

        if (request.triggerRobotId() != null) {
            affectedRobotIdSet.add(request.triggerRobotId());
        }

        for (TaskAssignmentResult assignmentResult
                : assignmentResults) {

            if (assignmentResult.getPreviousRobotId() != null) {
                affectedRobotIdSet.add(
                        assignmentResult.getPreviousRobotId()
                );
            }

            affectedRobotIdSet.add(
                    assignmentResult.getAssignedRobotId()
            );
        }

        if (response.routes() != null) {
            response.routes().stream()
                    .map(ReoptimizationResponse.RobotRoute::robotId)
                    .forEach(affectedRobotIdSet::add);
        }

        List<Long> affectedRobotIds =
                List.copyOf(affectedRobotIdSet);

        ReoptimizationCompletedEvent event =
                ReoptimizationCompletedEvent.of(
                        simulationRun.getId(),
                        savedResult.getId(),
                        response,
                        request.reason(),
                        request.triggerRobotId(),
                        changedTaskIds,
                        affectedRobotIds
                );

        String topic = REOPTIMIZATION_TOPIC_FORMAT.formatted(
                simulationRun.getId()
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

    private String convertNodePathToJson(List<Long> nodePath) {
        try {
            return objectMapper.writeValueAsString(nodePath);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "재최적화 경로 노드 목록을 JSON으로 변환하지 못했습니다.",
                    e
            );
        }
    }

    private void updateAssignedRobotState(
            Long simulationRunId,
            Task task,
            Robot assignedRobot
    ) {
        simulationRunStateStore
                .findByRobotId(
                        simulationRunId,
                        assignedRobot.getId()
                )
                .ifPresent(state ->
                        simulationRunStateStore.save(
                                simulationRunId,
                                RobotState.stationary(
                                        state.robotId(),
                                        state.warehouseId(),
                                        state.currentNodeId(),
                                        state.currentNodeCode(),
                                        state.batteryLevel(),
                                        RobotStatus.ASSIGNED,
                                        task.getId(),
                                        LocalDateTime.now()
                                )
                        )
                );
    }

    private void updateFailedRobotState(
            Long simulationRunId,
            ReoptimizationRequest request
    ) {
        if (request.reason() != ReoptimizationReason.ROBOT_FAILURE
                || request.triggerRobotId() == null) {
            return;
        }

        simulationRunStateStore
                .findByRobotId(
                        simulationRunId,
                        request.triggerRobotId()
                )
                .ifPresent(state ->
                        simulationRunStateStore.save(
                                simulationRunId,
                                RobotState.stationary(
                                        state.robotId(),
                                        state.warehouseId(),
                                        state.currentNodeId(),
                                        state.currentNodeCode(),
                                        state.batteryLevel(),
                                        RobotStatus.ERROR,
                                        null,
                                        LocalDateTime.now()
                                )
                        )
                );
    }
}