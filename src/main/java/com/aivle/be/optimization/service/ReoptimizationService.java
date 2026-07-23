package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.request.ReoptimizationRequest;
import com.aivle.be.optimization.dto.response.ReoptimizationResponse;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReoptimizationService {

    private static final List<TaskStatus> ACTIVE_TASK_STATUSES = List.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final OptimizationClient optimizationClient;
    private final RobotRepository robotRepository;

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

        applyAssignments(
                simulationRun,
                response
        );

        return response;
    }
    private void applyAssignments(
            SimulationRun simulationRun,
            ReoptimizationResponse response
    ) {
        if (response.assignments() == null) {
            return;
        }

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

            if (!task.getSimulationRun().getId()
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

            if (task.getStatus() == TaskStatus.PENDING) {
                task.assignRobot(robot);
            } else if (task.getStatus() == TaskStatus.ASSIGNED
                    || task.getStatus() == TaskStatus.IN_PROGRESS) {
                task.reassignRobot(robot);
            }
        }
    }
}