package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.dto.response.LaroPlanResponse;
import com.aivle.be.optimization.service.AiPostgresContractSyncService;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskInventoryService;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanTaskLifecycleService {

    private static final String TASK_TOPIC = "/topic/tasks";

    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskInventoryService taskInventoryService;
    private final AiPostgresContractSyncService aiPostgresContractSyncService;
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<Long, Set<Long>> installedTaskIds = new ConcurrentHashMap<>();

    @Transactional
    public void installAssignments(
            Long simulationRunId,
            List<LaroPlanResponse.LogicalOperation> operations
    ) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId);
        Set<Long> planTaskIds = ConcurrentHashMap.newKeySet();

        for (LaroPlanResponse.LogicalOperation operation : operations) {
            Task task = tasks.stream()
                    .filter(candidate -> matches(candidate, operation.operationId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "LARO logical operation does not match a BE task: "
                                    + operation.operationId()
                    ));
            Long robotId = parseRobotId(operation.assignedRobotId());
            Robot robot = robotRepository.findById(robotId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "LARO assigned robot does not exist in BE: " + robotId
                    ));
            if (task.getTaskType() == TaskType.INBOUND) {
                confirmInboundDestination(task, operation);
            }
            if (task.getStatus() == TaskStatus.PENDING) {
                task.assignFromValidatedPlan(robot);
            } else if (task.getStatus() == TaskStatus.ASSIGNED
                    || task.getStatus() == TaskStatus.IN_PROGRESS) {
                task.reassignRobot(robot);
            } else {
                throw new IllegalArgumentException(
                        "LARO plan contains a terminal BE task: " + task.getId()
                );
            }
            planTaskIds.add(task.getId());
            broadcast(task);
        }
        installedTaskIds.put(simulationRunId, Set.copyOf(planTaskIds));
    }

    private void confirmInboundDestination(
            Task task,
            LaroPlanResponse.LogicalOperation operation
    ) {
        String rackId = operation.targetRackId();
        Integer rackLevel = operation.targetRackLevel();
        String deliveryNode = operation.deliveryNode();
        if (rackId == null || rackId.isBlank() || rackLevel == null
                || deliveryNode == null || deliveryNode.isBlank()) {
            throw new IllegalArgumentException(
                    "LARO inbound operation is missing the selected putaway destination: "
                            + operation.operationId()
            );
        }
        WarehouseNode rackNode = warehouseNodeRepository
                .findByWarehouse_IdAndNodeCode(task.getWarehouse().getId(), rackId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "LARO selected an unknown BE rack node: " + rackId
                ));
        aiPostgresContractSyncService.confirmInboundPlacement(
                task, rackId, rackLevel, deliveryNode
        );
        task.confirmInboundDestination(rackNode);
    }

    @Transactional
    public void completeAssignedTasks(Long simulationRunId) {
        Set<Long> taskIds = installedTaskIds.remove(simulationRunId);
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        List<Task> tasks = taskRepository.findAllById(taskIds).stream()
                .filter(task -> task.getSimulationRun() != null
                        && simulationRunId.equals(task.getSimulationRun().getId()))
                .filter(task -> task.getStatus() == TaskStatus.ASSIGNED
                        || task.getStatus() == TaskStatus.IN_PROGRESS)
                .collect(Collectors.toList());
        for (Task task : tasks) {
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.start();
            }
            task.complete();
            taskInventoryService.applyCompletion(task);
            aiPostgresContractSyncService.syncTaskCompletion(task);
            broadcast(task);
        }
    }

    @Transactional
    public void releaseAssignments(Long simulationRunId) {
        installedTaskIds.remove(simulationRunId);
        aiPostgresContractSyncService.releaseInboundPlacements(simulationRunId);
    }

    private boolean matches(Task task, String operationId) {
        return operationId != null && (operationId.equals(task.getOperationId())
                || operationId.equals(task.getOrderId())
                || operationId.equals(task.getInboundId()));
    }

    private Long parseRobotId(String robotId) {
        try {
            return Long.valueOf(robotId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "LARO plan must return the BE numeric robot ID: " + robotId,
                    exception
            );
        }
    }

    private void broadcast(Task task) {
        messagingTemplate.convertAndSend(TASK_TOPIC, new TaskResponse(task));
    }
}
