package com.aivle.be.laro.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskCreateCommand;
import com.aivle.be.task.service.TaskCreationService;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LARO 응답의 문자열 계약을 BE의 영속 Task와 재생용 숫자 ID 계약으로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class LaroPlanExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LaroPlanExecutionService.class);

    private final SimulationRunRepository simulationRunRepository;
    private final TaskRepository taskRepository;
    private final TaskCreationService taskCreationService;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final SimulationPlaybackService simulationPlaybackService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 승인 대기 응답은 그대로 프론트에 돌려주고, READY 계획만 실행기로 넘긴다.
     */
    @Transactional
    public boolean activateIfReady(
            Long simulationRunId,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        PreparedExecution prepared = prepareReadyPlan(simulationRunId, request, response);
        if (prepared == null) {
            return false;
        }
        activatePrepared(prepared);
        return true;
    }

    /**
     * 응답이 현재 실행 세대에 속할 때만 READY 계획을 설치한다.
     * 실행 행을 잠가 초기화와 계획 설치가 서로 엇갈리지 않게 한다.
     */
    @Transactional
    public boolean activateIfReady(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        return activateIfReady(simulationRunId, request, response);
    }

    @Transactional
    public boolean stageReplanIfReady(
            Long simulationRunId,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        PreparedExecution prepared = prepareReadyPlan(simulationRunId, request, response);
        if (prepared == null) {
            return false;
        }
        stagePrepared(prepared);
        return true;
    }

    /** 현재 실행 세대의 재계획만 안전 지점 활성화 후보로 등록한다. */
    @Transactional
    public boolean stageReplanIfReady(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        return stageReplanIfReady(simulationRunId, request, response);
    }

    private void requireCurrentExecution(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        SimulationRun run = simulationRunRepository.findByIdForUpdate(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        if (run.getExecutionVersion() != expectedExecutionVersion) {
            throw new StaleSimulationExecutionException(
                    simulationRunId,
                    expectedExecutionVersion,
                    run.getExecutionVersion()
            );
        }
    }

    @Transactional
    public PreparedExecution prepareIfReady(
            Long simulationRunId,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        return prepareReadyPlan(simulationRunId, request, response);
    }

    private PreparedExecution prepareReadyPlan(
            Long simulationRunId,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        LaroPlanResponse.SimulationPlan plan = response == null || response.result() == null
                ? null
                : response.result().plan();
        if (plan == null || !"READY".equalsIgnoreCase(plan.status())) {
            return null;
        }
        if (plan.robots() == null || plan.robots().isEmpty()) {
            throw new BusinessException(ErrorCode.LARO_PLAN_NOT_EXECUTABLE);
        }

        SimulationRun run = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        if (response.simulationRunId() != null
                && !simulationRunId.equals(response.simulationRunId())) {
            throw mappingFailure(
                    "SIMULATION_RUN_ID_MISMATCH",
                    "simulationRunId", simulationRunId,
                    "responseSimulationRunId", response.simulationRunId(),
                    "planId", plan.planId()
            );
        }
        if (response.warehouseNumericId() != null
                && !run.getWarehouse().getId().equals(response.warehouseNumericId())) {
            throw mappingFailure(
                    "WAREHOUSE_ID_MISMATCH",
                    "simulationRunId", simulationRunId,
                    "warehouseId", run.getWarehouse().getId(),
                    "responseWarehouseId", response.warehouseNumericId(),
                    "planId", plan.planId()
            );
        }

        Map<String, LaroPlanRequest.StructuredOperation> requestedOperations = new LinkedHashMap<>();
        for (LaroPlanRequest.StructuredOperation operation : request.structuredInput().operations()) {
            requestedOperations.put(operation.operationId(), operation);
        }
        Map<String, LaroPlanResponse.LogicalOperation> logicalOperations = new HashMap<>();
        if (plan.logicalOperations() != null) {
            for (LaroPlanResponse.LogicalOperation operation : plan.logicalOperations()) {
                logicalOperations.put(operation.operationId(), operation);
            }
        }

        Map<String, Long> aiTaskToBeTask = new HashMap<>();
        for (LaroPlanRequest.StructuredOperation operation : requestedOperations.values()) {
            Task task = findOrCreateTask(
                    simulationRunId,
                    run,
                    operation,
                    logicalOperations.get(operation.operationId()),
                    plan
            );
            if (task == null) {
                continue;
            }
            bindTaskIdentifiers(aiTaskToBeTask, operation, logicalOperations.get(operation.operationId()), plan, task);
        }

        bindExistingPlanTasks(simulationRunId, logicalOperations, plan, aiTaskToBeTask);

        return new PreparedExecution(simulationRunId, plan, Map.copyOf(aiTaskToBeTask));
    }

    public void activatePrepared(PreparedExecution prepared) {
        simulationPlaybackService.installAiPlan(
                prepared.simulationRunId(), prepared.plan(), prepared.aiTaskToBeTask());
        markActivated(prepared.simulationRunId(), prepared.plan().planId());
    }

    public void stagePrepared(PreparedExecution prepared) {
        simulationPlaybackService.stageAiReplan(
                prepared.simulationRunId(), prepared.plan(), prepared.aiTaskToBeTask());
        markPending(prepared.simulationRunId(), prepared.plan().planId());
    }

    private void bindExistingPlanTasks(
            Long simulationRunId,
            Map<String, LaroPlanResponse.LogicalOperation> logicalOperations,
            LaroPlanResponse.SimulationPlan plan,
            Map<String, Long> bindings
    ) {
        for (LaroPlanResponse.LogicalOperation operation : logicalOperations.values()) {
            if (bindings.containsKey(operation.operationId())) {
                continue;
            }
            Task task = taskRepository
                    .findBySimulationRun_IdAndExternalOperationId(
                            simulationRunId, operation.operationId())
                    .orElse(null);
            if (task == null) {
                continue;
            }
            bindIdentifier(bindings, operation.operationId(), task.getId());
            Set<String> logicalTaskIds = new HashSet<>();
            if (operation.taskIds() != null) {
                for (String taskId : operation.taskIds()) {
                    bindIdentifier(bindings, taskId, task.getId());
                    logicalTaskIds.add(LaroTaskId.base(taskId));
                }
            }
            for (LaroPlanResponse.RobotPlan robot : plan.robots()) {
                if (robot.steps() == null) {
                    continue;
                }
                for (LaroPlanResponse.PlanStep step : robot.steps()) {
                    if (step.taskId() != null
                            && logicalTaskIds.contains(LaroTaskId.base(step.taskId()))) {
                        bindIdentifier(bindings, step.taskId(), task.getId());
                    }
                }
            }
        }
    }

    private Task findOrCreateTask(
            Long simulationRunId,
            SimulationRun run,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan
    ) {
        if (operation.operationType() != LaroPlanRequest.OperationType.INBOUND
                && operation.operationType() != LaroPlanRequest.OperationType.OUTBOUND) {
            return null;
        }
        Integer plannedRackLevel = plannedRackLevel(operation, logicalOperation);
        if (operation.taskId() != null) {
            Task task = taskRepository.findById(operation.taskId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
            if (task.getSimulationRun() == null
                    || !simulationRunId.equals(task.getSimulationRun().getId())) {
                throw new BusinessException(ErrorCode.TASK_SIMULATION_RUN_MISMATCH);
            }
            applyPhysicalStorageContract(
                    task,
                    run.getWarehouse().getId(),
                    operation,
                    logicalOperation,
                    plan,
                    plannedRackLevel
            );
            return task;
        }

        Task existing = taskRepository
                .findBySimulationRun_IdAndExternalOperationId(simulationRunId, operation.operationId())
                .orElse(null);
        if (existing != null) {
            applyPhysicalStorageContract(
                    existing,
                    run.getWarehouse().getId(),
                    operation,
                    logicalOperation,
                    plan,
                    plannedRackLevel
            );
            return existing;
        }

        WarehouseNode start = resolveStartNode(run.getWarehouse().getId(), operation, logicalOperation, plan);
        WarehouseNode end = resolveEndNode(run.getWarehouse().getId(), operation, logicalOperation, plan);
        TaskType taskType = operation.operationType() == LaroPlanRequest.OperationType.INBOUND
                ? TaskType.INBOUND
                : TaskType.OUTBOUND;
        Integer releaseAtSeconds = operation.releaseAtMs() == null
                ? null
                : Math.toIntExact(operation.releaseAtMs() / 1000L);

        return taskCreationService.create(new TaskCreateCommand(
                run.getWarehouse().getId(),
                start.getId(),
                end.getId(),
                operation.sourceWarehouseItemId(),
                operation.itemId(),
                taskType,
                simulationRunId,
                operation.quantity(),
                releaseAtSeconds,
                operation.operationId(),
                plannedRackLevel
        ));
    }

    private void applyPhysicalStorageContract(
            Task task,
            Long warehouseId,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan,
            Integer rackLevel
    ) {
        if (task.getTaskType() != TaskType.INBOUND) {
            return;
        }
        WarehouseNode rackNode = resolveEndNode(
                warehouseId, operation, logicalOperation, plan);
        try {
            task.planInboundDestination(rackNode, rackLevel);
        } catch (LaroPlanMappingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw mappingFailure(
                    "INBOUND_PHYSICAL_CONTRACT_REJECTED",
                    exception,
                    "simulationRunId", task.getSimulationRun() == null
                            ? null : task.getSimulationRun().getId(),
                    "warehouseId", warehouseId,
                    "planId", plan == null ? null : plan.planId(),
                    "planVersion", plan == null ? null : plan.planVersion(),
                    "basePlanId", plan == null ? null : plan.basePlanId(),
                    "operationId", operation == null ? null : operation.operationId(),
                    "operationType", operation == null ? null : operation.operationType(),
                    "taskId", task.getId(),
                    "taskStatus", task.getStatus(),
                    "inventoryApplied", task.isInventoryApplied(),
                    "existingRackId", task.getEndNode() == null
                            ? null : task.getEndNode().getId(),
                    "existingRackCode", task.getEndNode() == null
                            ? null : task.getEndNode().getNodeCode(),
                    "existingRackLevel", task.getTargetRackLevel(),
                    "requestedRackId", rackNode == null ? null : rackNode.getId(),
                    "requestedRackCode", rackNode == null ? null : rackNode.getNodeCode(),
                    "requestedRackLevel", rackLevel,
                    "logicalRackId", logicalOperation == null
                            ? null : logicalOperation.rackId(),
                    "logicalRackLevel", logicalOperation == null
                            ? null : logicalOperation.rackLevel(),
                    "causeType", exception.getClass().getSimpleName(),
                    "causeMessage", exception.getMessage()
            );
        }
    }

    private Integer plannedRackLevel(
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation
    ) {
        // targetRackLevel is the physical putaway destination for INBOUND only.
        // An OUTBOUND logical rack level describes the source inventory and is
        // already preserved by sourceWarehouseItemId/startNode.
        if (operation.operationType() != LaroPlanRequest.OperationType.INBOUND) {
            return null;
        }
        if (operation.targetRackLevel() != null) {
            return operation.targetRackLevel();
        }
        return logicalOperation == null ? null : logicalOperation.rackLevel();
    }

    private WarehouseNode resolveStartNode(
            Long warehouseId,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan
    ) {
        if (operation.operationType() == LaroPlanRequest.OperationType.INBOUND) {
            WarehouseNode handoffNode = resolveServiceNode(
                    warehouseId,
                    logicalOperation,
                    plan,
                    Set.of("PICKUP"),
                    false
            );
            if (handoffNode != null) {
                return handoffNode;
            }
        }
        WarehouseNode direct = resolveNode(warehouseId, operation.sourceNodeId(), operation.sourceNodeCode());
        if (direct != null
                && (operation.operationType() != LaroPlanRequest.OperationType.OUTBOUND
                || direct.getNodeType() == NodeType.RACK_STORAGE)) {
            return direct;
        }
        if (operation.operationType() == LaroPlanRequest.OperationType.OUTBOUND) {
            WarehouseNode rackNode = resolveRackNode(warehouseId, logicalOperation);
            if (rackNode != null) {
                return rackNode;
            }
            throw operationMappingFailure(
                    "OUTBOUND_SOURCE_NODE_UNRESOLVED",
                    warehouseId,
                    operation,
                    logicalOperation,
                    plan
            );
        }
        WarehouseNode serviceNode = resolveServiceNode(warehouseId, logicalOperation, plan, Set.of("PICKUP"), false);
        if (serviceNode != null) {
            return serviceNode;
        }
        throw operationMappingFailure(
                "INBOUND_SOURCE_NODE_UNRESOLVED",
                warehouseId,
                operation,
                logicalOperation,
                plan
        );
    }

    private WarehouseNode resolveEndNode(
            Long warehouseId,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan
    ) {
        if (operation.operationType() == LaroPlanRequest.OperationType.INBOUND) {
            WarehouseNode rackNode = resolveRackNode(warehouseId, logicalOperation);
            if (rackNode != null) {
                return rackNode;
            }
            WarehouseNode directRack = resolveNode(
                    warehouseId, operation.destinationNodeId(), operation.destinationNodeCode());
            if (directRack != null && directRack.getNodeType() == NodeType.RACK_STORAGE) {
                return directRack;
            }
            throw operationMappingFailure(
                    "INBOUND_DESTINATION_RACK_UNRESOLVED",
                    warehouseId,
                    operation,
                    logicalOperation,
                    plan
            );
        }
        WarehouseNode direct = resolveNode(warehouseId, operation.destinationNodeId(), operation.destinationNodeCode());
        if (direct != null) {
            return direct;
        }
        if (logicalOperation != null && logicalOperation.logicalDestinationId() != null) {
            for (String destination : logicalOperation.logicalDestinationId().split(",")) {
                WarehouseNode logicalDestination = resolveNode(
                        warehouseId,
                        null,
                        destination.trim()
                );
                if (logicalDestination != null) {
                    return logicalDestination;
                }
            }
        }
        WarehouseNode serviceNode = resolveServiceNode(
                warehouseId,
                logicalOperation,
                plan,
                Set.of("DROP", "STATION"),
                true
        );
        if (serviceNode != null) {
            return serviceNode;
        }
        throw operationMappingFailure(
                "OUTBOUND_DESTINATION_NODE_UNRESOLVED",
                warehouseId,
                operation,
                logicalOperation,
                plan
        );
    }

    private LaroPlanMappingException operationMappingFailure(
            String reason,
            Long warehouseId,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan
    ) {
        return mappingFailure(
                reason,
                "warehouseId", warehouseId,
                "planId", plan == null ? null : plan.planId(),
                "operationId", operation == null ? null : operation.operationId(),
                "operationType", operation == null ? null : operation.operationType(),
                "sourceNodeId", operation == null ? null : operation.sourceNodeId(),
                "sourceNodeCode", operation == null ? null : operation.sourceNodeCode(),
                "destinationNodeId", operation == null ? null : operation.destinationNodeId(),
                "destinationNodeCode", operation == null ? null : operation.destinationNodeCode(),
                "logicalRackId", logicalOperation == null ? null : logicalOperation.rackId(),
                "logicalDestinationId", logicalOperation == null
                        ? null : logicalOperation.logicalDestinationId()
        );
    }

    private LaroPlanMappingException mappingFailure(String reason, Object... context) {
        return mappingFailure(reason, null, context);
    }

    private LaroPlanMappingException mappingFailure(
            String reason,
            Throwable cause,
            Object... context
    ) {
        LaroPlanMappingException exception = new LaroPlanMappingException(
                reason,
                cause,
                context
        );
        log.warn("[LARO plan mapping] {}", exception.getMessage());
        return exception;
    }

    private WarehouseNode resolveRackNode(
            Long warehouseId,
            LaroPlanResponse.LogicalOperation logicalOperation
    ) {
        if (logicalOperation == null) {
            return null;
        }
        WarehouseNode byRackId = resolveNode(warehouseId, null, logicalOperation.rackId());
        if (byRackId != null && byRackId.getNodeType() == NodeType.RACK_STORAGE) {
            return byRackId;
        }
        if (logicalOperation.logicalDestinationId() == null) {
            return null;
        }
        for (String destination : logicalOperation.logicalDestinationId().split(",")) {
            WarehouseNode candidate = resolveNode(warehouseId, null, destination.trim());
            if (candidate != null && candidate.getNodeType() == NodeType.RACK_STORAGE) {
                return candidate;
            }
        }
        return null;
    }

    private WarehouseNode resolveNode(Long warehouseId, Long nodeId, String nodeCode) {
        if (nodeId != null) {
            WarehouseNode node = warehouseNodeRepository.findByIdAndActiveTrue(nodeId).orElse(null);
            if (node != null && warehouseId.equals(node.getWarehouse().getId())) {
                return node;
            }
        }
        if (nodeCode == null || nodeCode.isBlank()) {
            return null;
        }
        return warehouseNodeRepository
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(warehouseId, nodeCode)
                .orElse(null);
    }

    private WarehouseNode resolveServiceNode(
            Long warehouseId,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan,
            Set<String> wantedKinds,
            boolean last
    ) {
        Set<String> logicalTaskIds = logicalOperation == null || logicalOperation.taskIds() == null
                ? Set.of()
                : logicalOperation.taskIds().stream()
                .map(LaroTaskId::base)
                .collect(Collectors.toSet());
        LaroPlanResponse.PlanStep selected = null;
        for (LaroPlanResponse.RobotPlan robot : plan.robots()) {
            if (robot.steps() == null) {
                continue;
            }
            for (LaroPlanResponse.PlanStep step : robot.steps()) {
                if (!"SERVICE".equalsIgnoreCase(step.stepType())
                        || step.nodeId() == null
                        || step.serviceKind() == null
                        || !wantedKinds.contains(step.serviceKind().toUpperCase())) {
                    continue;
                }
                if (!logicalTaskIds.isEmpty()
                        && !logicalTaskIds.contains(LaroTaskId.base(step.taskId()))) {
                    continue;
                }
                if (selected == null || (last && endsAfter(step, selected))) {
                    selected = step;
                }
            }
        }
        return selected == null ? null : resolveNode(warehouseId, null, selected.nodeId());
    }

    private boolean endsAfter(LaroPlanResponse.PlanStep left, LaroPlanResponse.PlanStep right) {
        long leftEnd = left.endAtMs() == null ? 0 : left.endAtMs();
        long rightEnd = right.endAtMs() == null ? 0 : right.endAtMs();
        return leftEnd > rightEnd;
    }

    private void bindTaskIdentifiers(
            Map<String, Long> bindings,
            LaroPlanRequest.StructuredOperation operation,
            LaroPlanResponse.LogicalOperation logicalOperation,
            LaroPlanResponse.SimulationPlan plan,
            Task task
    ) {
        bindIdentifier(bindings, operation.operationId(), task.getId());
        Set<String> logicalTaskIds = new HashSet<>();
        if (logicalOperation != null && logicalOperation.taskIds() != null) {
            for (String taskId : logicalOperation.taskIds()) {
                if (taskId != null) {
                    bindIdentifier(bindings, taskId, task.getId());
                    logicalTaskIds.add(LaroTaskId.base(taskId));
                }
            }
        }
        for (LaroPlanResponse.RobotPlan robot : plan.robots()) {
            if (robot.steps() == null) {
                continue;
            }
            for (LaroPlanResponse.PlanStep step : robot.steps()) {
                String aiTaskId = step.taskId();
                if (aiTaskId != null
                        && ((!logicalTaskIds.isEmpty()
                        && logicalTaskIds.contains(LaroTaskId.base(aiTaskId)))
                        || (logicalTaskIds.isEmpty()
                        && aiTaskId.startsWith(operation.operationId())))) {
                    bindIdentifier(bindings, aiTaskId, task.getId());
                }
            }
        }
    }

    private void bindIdentifier(Map<String, Long> bindings, String identifier, Long taskId) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        bindings.putIfAbsent(identifier, taskId);
        bindings.putIfAbsent(LaroTaskId.base(identifier), taskId);
    }

    private void markActivated(Long simulationRunId, String planId) {
        if (planId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "update laro_ext.simulation_plan set activated_at = now() "
                            + "where plan_id = ? and simulation_run_id = ?",
                    planId,
                    simulationRunId
            );
        } catch (RuntimeException exception) {
            log.warn("LARO plan activation timestamp update failed: planId={}, reason={}",
                    planId, exception.getMessage());
        }
    }

    private void markPending(Long simulationRunId, String planId) {
        if (planId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "update laro_ext.simulation_plan set status = 'PENDING_ACTIVATION' "
                            + "where plan_id = ? and simulation_run_id = ?",
                    planId,
                    simulationRunId
            );
        } catch (RuntimeException exception) {
            log.warn("LARO pending plan status update failed: planId={}, reason={}",
                    planId, exception.getMessage());
        }
    }

    public record PreparedExecution(
            Long simulationRunId,
            LaroPlanResponse.SimulationPlan plan,
            Map<String, Long> aiTaskToBeTask
    ) {}
}
