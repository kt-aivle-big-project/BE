package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.entity.ReoptimizationStagedRobotSnapshot;
import com.aivle.be.optimization.entity.ReoptimizationStagedTaskPlan;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReoptimizationPlanApplicationService {

    private static final Set<TaskStatus> ACTIVE_TASK_STATUSES =
            EnumSet.of(
                    TaskStatus.PENDING,
                    TaskStatus.ASSIGNED,
                    TaskStatus.IN_PROGRESS
            );

    private final SimulationRunRepository simulationRunRepository;
    private final ReoptimizationPlanStageRepository stageRepository;
    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final SimulationRunRobotRepository participantRepository;

    /**
     * Lock 순서: SimulationRun -> stage -> Task(id 오름차순)
     * -> Robot(id 오름차순). 모든 검증 후에만 Task를 변경한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReoptimizationActivationPlan apply(
            Long simulationRunId,
            String replanId,
            Long snapshotVersion
    ) {
        try {
            SimulationRun simulationRun = simulationRunRepository
                    .findByIdForUpdate(simulationRunId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.SIMULATION_RUN_NOT_FOUND
                    ));
            if (simulationRun.getStatus()
                    != SimulationRunStatus.REPLANNING) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_PLAN_STALE
                );
            }

            ReoptimizationPlanStage stage = findExactStage(
                    simulationRunId,
                    replanId,
                    snapshotVersion
            );
            if (stage.getStatus()
                    == ReoptimizationPlanStage.Status.DB_APPLIED
                    || stage.getStatus()
                    == ReoptimizationPlanStage.Status.ACTIVATED) {
                return ReoptimizationActivationPlan.from(stage);
            }
            if (stage.getStatus()
                    != ReoptimizationPlanStage.Status.STAGED) {
                throw new BusinessException(
                        ErrorCode.REOPTIMIZATION_PLAN_STAGE_INVALID_STATUS
                );
            }

            applyStagedTasks(simulationRun, stage);
            stage.markDbApplied();
            stageRepository.flush();
            return ReoptimizationActivationPlan.from(stage);
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_APPLY_FAILED,
                    exception
            );
        }
    }

    private ReoptimizationPlanStage findExactStage(
            Long simulationRunId,
            String replanId,
            Long snapshotVersion
    ) {
        return stageRepository.findExactForUpdate(
                        simulationRunId,
                        replanId,
                        snapshotVersion
                )
                .orElseGet(() -> {
                    boolean correlationExists = stageRepository
                            .findByReplanId(replanId)
                            .isPresent();
                    boolean anotherCurrentStage = stageRepository
                            .findFirstBySimulationRun_IdAndStatusOrderByCreatedAtDescIdDesc(
                                    simulationRunId,
                                    ReoptimizationPlanStage.Status.STAGED
                            )
                            .isPresent();
                    if (correlationExists || anotherCurrentStage) {
                        throw new BusinessException(
                                ErrorCode.REOPTIMIZATION_PLAN_STALE
                        );
                    }
                    throw new BusinessException(
                            ErrorCode.REOPTIMIZATION_PLAN_STAGE_NOT_FOUND
                    );
                });
    }

    private void applyStagedTasks(
            SimulationRun simulationRun,
            ReoptimizationPlanStage stage
    ) {
        List<ReoptimizationStagedTaskPlan> plans = stage.getTaskPlans();
        Set<Long> plannedTaskIds = plans.stream()
                .map(ReoptimizationStagedTaskPlan::getTaskId)
                .collect(Collectors.toSet());
        if (plannedTaskIds.size() != plans.size()) {
            taskStateChanged();
        }

        List<Task> lockedTasks = taskRepository
                .findAllBySimulationRunIdForUpdateOrderById(
                        simulationRun.getId()
                );
        Set<Long> activeTaskIds = lockedTasks.stream()
                .filter(task -> ACTIVE_TASK_STATUSES.contains(
                        task.getStatus()
                ))
                .map(Task::getId)
                .collect(Collectors.toSet());
        if (!activeTaskIds.equals(plannedTaskIds)) {
            taskStateChanged();
        }

        Map<Long, ReoptimizationStagedTaskPlan> plansByTaskId =
                plans.stream().collect(Collectors.toMap(
                        ReoptimizationStagedTaskPlan::getTaskId,
                        Function.identity()
                ));
        Map<Long, Task> tasksById = lockedTasks.stream()
                .collect(Collectors.toMap(
                        Task::getId,
                        Function.identity()
                ));

        List<Long> robotIds = plans.stream()
                .map(ReoptimizationStagedTaskPlan::getRobotId)
                .distinct()
                .sorted()
                .toList();
        List<Robot> lockedRobots = robotRepository
                .findAllByIdInForUpdateOrderById(robotIds);
        Map<Long, Robot> robotsById = lockedRobots.stream()
                .collect(Collectors.toMap(
                        Robot::getId,
                        Function.identity()
                ));
        if (robotsById.size() != robotIds.size()) {
            robotInvalid();
        }

        Set<Long> participantRobotIds = participantRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(
                        simulationRun.getId()
                ).stream()
                .map(participant -> participant.getRobot().getId())
                .collect(Collectors.toSet());
        Map<Long, ReoptimizationStagedRobotSnapshot> snapshotsByRobotId =
                stage.getRobotSnapshots().stream()
                .collect(Collectors.toMap(
                        ReoptimizationStagedRobotSnapshot::getRobotId,
                        Function.identity()
                ));

        validateRobots(
                simulationRun,
                robotIds,
                robotsById,
                participantRobotIds,
                snapshotsByRobotId
        );

        for (Long taskId : plannedTaskIds.stream().sorted().toList()) {
            validateTask(
                    simulationRun,
                    tasksById.get(taskId),
                    plansByTaskId.get(taskId),
                    snapshotsByRobotId
            );
        }

        // 모든 결정적 검증이 끝난 뒤 id 오름차순으로만 변경한다.
        for (Long taskId : plannedTaskIds.stream().sorted().toList()) {
            applyTask(
                    tasksById.get(taskId),
                    plansByTaskId.get(taskId),
                    robotsById
            );
        }
    }

    private void validateRobots(
            SimulationRun simulationRun,
            List<Long> robotIds,
            Map<Long, Robot> robotsById,
            Set<Long> participantRobotIds,
            Map<Long, ReoptimizationStagedRobotSnapshot> snapshotsByRobotId
    ) {
        Long warehouseId = simulationRun.getWarehouse().getId();
        for (Long robotId : robotIds) {
            Robot robot = robotsById.get(robotId);
            ReoptimizationStagedRobotSnapshot snapshot =
                    snapshotsByRobotId.get(robotId);
            if (robot == null
                    || snapshot == null
                    || !participantRobotIds.contains(robotId)
                    || !warehouseId.equals(robot.getWarehouse().getId())
                    || robot.getStatus()
                    != RobotAvailabilityStatus.AVAILABLE
                    || "ERROR".equals(snapshot.getStatus())
                    || "OFFLINE".equals(snapshot.getStatus())) {
                robotInvalid();
            }
        }
    }

    private void validateTask(
            SimulationRun simulationRun,
            Task task,
            ReoptimizationStagedTaskPlan plan,
            Map<Long, ReoptimizationStagedRobotSnapshot> snapshotsByRobotId
    ) {
        if (task == null || plan == null
                || task.getSimulationRun() == null
                || !simulationRun.getId().equals(
                        task.getSimulationRun().getId()
                )
                || !simulationRun.getWarehouse().getId().equals(
                        task.getWarehouse().getId()
                )
                || !plan.getStartNodeId().equals(
                        task.getStartNode().getId()
                )
                || !plan.getEndNodeId().equals(
                        task.getEndNode().getId()
                )) {
            taskStateChanged();
        }

        TaskStatus snapshotStatus;
        try {
            snapshotStatus = TaskStatus.valueOf(
                    plan.getSnapshotTaskStatus()
            );
        } catch (RuntimeException exception) {
            taskStateChanged();
            return;
        }
        Long currentRobotId = task.getRobot() == null
                ? null
                : task.getRobot().getId();
        if (task.getStatus() != snapshotStatus
                || !Objects.equals(
                        currentRobotId,
                        plan.getSnapshotAssignedRobotId()
                )
                || !ACTIVE_TASK_STATUSES.contains(task.getStatus())) {
            taskStateChanged();
        }

        if (plan.getExecutionStage()
                == TaskPlan.ExecutionStage.TO_END) {
            ReoptimizationStagedRobotSnapshot snapshot =
                    snapshotsByRobotId.get(plan.getRobotId());
            if (plan.getSequence() != 0
                    || task.getStatus() != TaskStatus.IN_PROGRESS
                    || !plan.getRobotId().equals(currentRobotId)
                    || snapshot == null
                    || !plan.getTaskId().equals(
                            snapshot.getCurrentTaskId()
                    )
                    || snapshot.getRemainingStage()
                    != ReoptimizationOptimizationRequest
                    .RemainingStage.TO_END) {
                taskStateChanged();
            }
        } else if (task.getStatus() == TaskStatus.PENDING
                && currentRobotId != null) {
            taskStateChanged();
        }
    }

    private void applyTask(
            Task task,
            ReoptimizationStagedTaskPlan plan,
            Map<Long, Robot> robotsById
    ) {
        if (plan.getExecutionStage()
                == TaskPlan.ExecutionStage.TO_END) {
            return;
        }

        Robot plannedRobot = robotsById.get(plan.getRobotId());
        Long currentRobotId = task.getRobot() == null
                ? null
                : task.getRobot().getId();
        if (plan.getRobotId().equals(currentRobotId)) {
            return;
        }

        if (task.getStatus() == TaskStatus.PENDING) {
            task.assignRobot(plannedRobot);
        } else {
            task.reassignRobot(plannedRobot);
        }
    }

    private void taskStateChanged() {
        throw new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_TASK_STATE_CHANGED
        );
    }

    private void robotInvalid() {
        throw new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );
    }
}
