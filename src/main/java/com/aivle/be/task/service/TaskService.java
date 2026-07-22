package com.aivle.be.task.service;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.service.SimulationRunProgressService;
import com.aivle.be.task.controller.request.TaskAssignRequest;
import com.aivle.be.task.controller.request.TaskCreateRequest;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private static final String TOPIC = "/topic/tasks";

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final TaskCreationService taskCreationService;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final SimulationRunProgressService simulationRunProgressService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public TaskResponse createTask(TaskCreateRequest request) {
        Task saved = taskCreationService.create(new TaskCreateCommand(
                request.warehouseId(),
                request.startNodeId(),
                request.endNodeId(),
                request.warehouseItemId(),
                request.taskType(),
                request.simulationRunId()
        ));
        return new TaskResponse(saved);
    }

    public TaskResponse getTask(Long taskId) {
        return new TaskResponse(findTaskOrThrow(taskId));
    }

    public List<TaskResponse> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(TaskResponse::new)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksBySimulationRun(Long simulationRunId) {
        return taskRepository.findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId)
                .stream()
                .map(TaskResponse::new)
                .toList();
    }

    @Transactional
    public TaskResponse assignRobot(Long taskId, TaskAssignRequest request) {
        Task task = findTaskOrThrow(taskId);
        requireRunningSimulation(task);

        // Serialize assignments for the same robot so concurrent requests cannot
        // both pass the availability check.
        Robot robot = robotRepository.findByIdForUpdate(request.robotId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));

        boolean alreadyWorking = taskRepository.existsByRobot_IdAndStatusIn(
                robot.getId(), List.of(TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS));
        if (alreadyWorking) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_AVAILABLE);
        }

        if (!task.getWarehouse().getId().equals(robot.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.TASK_SIMULATION_RUN_MISMATCH);
        }
        if (task.getSimulationRun() != null
                && !simulationRunRobotRepository.existsBySimulationRun_IdAndRobot_Id(
                task.getSimulationRun().getId(),
                robot.getId()
        )) {
            throw new BusinessException(ErrorCode.TASK_ROBOT_NOT_PARTICIPANT);
        }

        task.assignRobot(robot);

        return broadcast(task);
    }

    @Transactional
    public TaskResponse startTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        requireRunningSimulation(task);
        task.start();
        return broadcast(task);
    }

    @Transactional
    public TaskResponse completeTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.complete();
        TaskResponse response = broadcast(task);
        evaluateRun(task);
        return response;
    }

    @Transactional
    public TaskResponse failTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.fail();
        TaskResponse response = broadcast(task);
        evaluateRun(task);
        return response;
    }

    @Transactional
    public void cancelTask(Long taskId) {
        Task task = findTaskOrThrow(taskId);
        task.cancel();
        broadcast(task);
        evaluateRun(task);
    }

    private TaskResponse broadcast(Task task) {
        TaskResponse response = new TaskResponse(task);
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }

    private Task findTaskOrThrow(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private void requireRunningSimulation(Task task) {
        if (task.getSimulationRun() != null
                && task.getSimulationRun().getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.TASK_REQUIRES_RUNNING_SIMULATION);
        }
    }

    private void evaluateRun(Task task) {
        Long simulationRunId = task.getSimulationRun() == null
                ? null
                : task.getSimulationRun().getId();
        simulationRunProgressService.evaluateAfterTaskFinished(simulationRunId);
    }
}
