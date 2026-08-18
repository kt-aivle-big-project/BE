package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebinds a replan request to the physical Task contracts already committed by BE.
 *
 * <p>The first command request is created before the AI chooses an inbound rack and
 * rack level.  Copying that request verbatim during a low-battery replan loses the
 * destination persisted on {@link Task}, allowing the AI to select the same empty
 * rack slot again.  This factory keeps the business operation intact while adding
 * the authoritative task ID and physical storage destination.</p>
 */
@Service
@RequiredArgsConstructor
public class SimulationReplanPlanRequestFactory {

    private static final Logger log = LoggerFactory.getLogger(
            SimulationReplanPlanRequestFactory.class
    );

    private final TaskRepository taskRepository;

    @Transactional(readOnly = true)
    public LaroPlanRequest enrichWithCurrentTaskContracts(
            Long simulationRunId,
            LaroPlanRequest request
    ) {
        if (request == null || request.structuredInput() == null) {
            return request;
        }

        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId);
        Map<Long, Task> byId = new HashMap<>();
        Map<String, Task> byOperationId = new HashMap<>();
        for (Task task : tasks) {
            if (task.getId() != null) {
                byId.put(task.getId(), task);
            }
            if (task.getExternalOperationId() != null
                    && !task.getExternalOperationId().isBlank()) {
                byOperationId.put(task.getExternalOperationId(), task);
            }
        }

        LaroPlanRequest.StructuredInput previous = request.structuredInput();
        List<LaroPlanRequest.StructuredOperation> operations = previous.operations()
                .stream()
                .map(operation -> enrichOperation(operation, byId, byOperationId))
                .toList();
        long taskBoundCount = operations.stream()
                .filter(operation -> operation.taskId() != null)
                .count();
        long inboundRackBoundCount = operations.stream()
                .filter(operation -> operation.operationType()
                        == LaroPlanRequest.OperationType.INBOUND)
                .filter(operation -> operation.destinationNodeCode() != null
                        && operation.targetRackLevel() != null)
                .count();
        log.info(
                "[replan task contract] runId={}, requestId={}, operations={}, "
                        + "taskBound={}, inboundRackBound={}",
                simulationRunId,
                previous.requestId(),
                operations.size(),
                taskBoundCount,
                inboundRackBoundCount
        );

        return new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        previous.requestId(),
                        operations,
                        previous.constraints(),
                        previous.routingContext()
                ),
                request.userCommand(),
                request.optimizationBackend(),
                request.runtimeSnapshot()
        );
    }

    private static LaroPlanRequest.StructuredOperation enrichOperation(
            LaroPlanRequest.StructuredOperation operation,
            Map<Long, Task> byId,
            Map<String, Task> byOperationId
    ) {
        Task task = operation.taskId() == null
                ? byOperationId.get(operation.operationId())
                : byId.get(operation.taskId());
        if (task == null || !sameOperationType(operation, task)) {
            return operation;
        }

        boolean inbound = operation.operationType()
                == LaroPlanRequest.OperationType.INBOUND;
        boolean outbound = operation.operationType()
                == LaroPlanRequest.OperationType.OUTBOUND;

        return new LaroPlanRequest.StructuredOperation(
                operation.operationId(),
                operation.operationType(),
                task.getId(),
                operation.itemId(),
                operation.productCode(),
                operation.quantity(),
                operation.priority(),

                operation.sourceWarehouseItemId(),
                operation.sourceStorageLocationId(),
                outbound && task.getStartNode() != null
                        ? task.getStartNode().getId()
                        : operation.sourceNodeId(),
                outbound && task.getStartNode() != null
                        ? task.getStartNode().getNodeCode()
                        : operation.sourceNodeCode(),
                operation.sourceFacilityCode(),

                operation.destinationStorageLocationId(),
                inbound && task.getEndNode() != null
                        ? task.getEndNode().getId()
                        : operation.destinationNodeId(),
                inbound && task.getEndNode() != null
                        ? task.getEndNode().getNodeCode()
                        : operation.destinationNodeCode(),
                operation.destinationFacilityCode(),
                inbound ? task.getTargetRackLevel() : operation.targetRackLevel(),

                operation.releaseAtMs(),
                operation.pickupServiceTimeMs(),
                operation.dropServiceTimeMs(),
                operation.attributes()
        );
    }

    private static boolean sameOperationType(
            LaroPlanRequest.StructuredOperation operation,
            Task task
    ) {
        return (operation.operationType() == LaroPlanRequest.OperationType.INBOUND
                && task.getTaskType() == TaskType.INBOUND)
                || (operation.operationType() == LaroPlanRequest.OperationType.OUTBOUND
                && task.getTaskType() == TaskType.OUTBOUND);
    }
}
