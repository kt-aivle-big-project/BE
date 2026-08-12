package com.aivle.be.fulfillmentcommand.service;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Java selector for the authoritative operation batch.
 *
 * <p>LLM is deliberately not involved in mode, count, product, or BOX selection.
 * It may only add an expression to the already selected operations later.</p>
 */
@Service
@RequiredArgsConstructor
public class FulfillmentCommandRandomSelector {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentCommandRandomSelector.class);
    private static final int MAX_TASKS_PER_ROBOT = 5;
    private static final int MAX_AUTOMATIC_BATCH_SIZE = 50;
    private static final int[] DEFAULT_WORKLOAD_MULTIPLIER_WEIGHTS = {10, 15, 20, 25, 30};
    private static final long SELECTION_SALT = 0x4F1BBCDCBFA54001L;
    private static final long EXPRESSION_SALT = 0x632BE59BD9B4E019L;
    private static final Set<TaskStatus> ACTIVE_TASK_STATUSES = EnumSet.of(
            TaskStatus.PENDING,
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final ProductRepository productRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskRepository taskRepository;

    @Value("${simulation.command-generation.workload-multiplier-weights:10,15,20,25,30}")
    private String workloadMultiplierWeights = "10,15,20,25,30";

    @Transactional(readOnly = true)
    public FulfillmentCommandSelection select(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request
    ) {
        SimulationRun run = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        Long warehouseId = run.getWarehouse().getId();
        long seed = ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE);
        Random random = new Random(seed ^ SELECTION_SALT);

        List<Product> inboundProducts = filteredProducts(request.inboundProductCodes());
        List<WarehouseItem> allItems = warehouseItemRepository.findAllByWarehouse_Id(warehouseId);
        Set<Long> reservedItemIds = taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        simulationRunId,
                        ACTIVE_TASK_STATUSES
                )
                .stream()
                .map(task -> task.getWarehouseItem() == null ? null : task.getWarehouseItem().getId())
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        Set<String> outboundFilter = normalizedCodes(request.outboundProductCodes());
        List<WarehouseItem> outboundBoxes = allItems.stream()
                .filter(item -> item.getQuantity() != null && item.getQuantity() > 0)
                .filter(item -> item.getProduct() != null)
                .filter(item -> !reservedItemIds.contains(item.getId()))
                .filter(item -> outboundFilter.isEmpty()
                        || outboundFilter.contains(normalize(item.getProduct().getProductCode())))
                .collect(Collectors.toCollection(ArrayList::new));

        int totalSlots =
                storageLocationRepository.findAllByWarehouse_Id(warehouseId).size() * 3;

        long occupiedSlots = allItems.stream()
                .filter(item -> item.getQuantity() != null && item.getQuantity() > 0)
                .count();

        int emptySlots = Math.max(
                0,
                totalSlots - Math.toIntExact(occupiedSlots)
        );
        boolean hasInboundAccess = !warehouseNodeRepository
                .findAllByWarehouse_IdAndNodeTypeAndActiveTrue(
                        warehouseId,
                        NodeType.INBOUND_HANDOFF_ACCESS
                )
                .isEmpty();
        boolean hasOutboundAccess = !warehouseNodeRepository
                .findAllByWarehouse_IdAndNodeTypeAndActiveTrue(
                        warehouseId,
                        NodeType.OUTBOUND_STATION_ACCESS
                )
                .isEmpty();
        boolean canInbound = hasInboundAccess && emptySlots > 0 && !inboundProducts.isEmpty();
        boolean canOutbound = hasOutboundAccess && !outboundBoxes.isEmpty();
        FulfillmentCommandMode mode = resolveMode(request.mode(), canInbound, canOutbound, random);

        int participantCount = simulationRunRobotRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(simulationRunId)
                .size();
        require(participantCount > 0);
        List<Integer> robotWorkloads = new ArrayList<>(participantCount);
        int automaticLimit;
        if (request.averageTasksPerRobot() != null) {
            automaticLimit = Math.max(
                    1,
                    (int) Math.round(participantCount * request.averageTasksPerRobot())
            );
        } else {
            automaticLimit = 0;
            for (int index = 0; index < participantCount; index++) {
                int robotWorkload = selectWorkloadMultiplier(random);
                robotWorkloads.add(robotWorkload);
                automaticLimit += robotWorkload;
            }
        }
        if (mode == FulfillmentCommandMode.BOTH) {
            automaticLimit = Math.max(2, automaticLimit);
        }
        automaticLimit = Math.min(MAX_AUTOMATIC_BATCH_SIZE, automaticLimit);
        OperationCounts counts = resolveCounts(
                request,
                mode,
                emptySlots,
                outboundBoxes.size(),
                automaticLimit,
                random
        );
        int inboundCount = counts.inbound();
        int outboundCount = counts.outbound();
        log.info(
                "[fulfillment-command] runId={}, robotWorkloads={}, averageTasksPerRobot={}, robots={}, target={}, inbound={}, outbound={}",
                simulationRunId,
                robotWorkloads,
                request.averageTasksPerRobot(),
                participantCount,
                automaticLimit,
                inboundCount,
                outboundCount
        );

        List<FulfillmentCommandSelection.Operation> operations = new ArrayList<>(
                inboundCount + outboundCount
        );
        Collections.shuffle(inboundProducts, random);
        for (int index = 0; index < inboundCount; index++) {
            Product product = inboundProducts.get(index % inboundProducts.size());
            operations.add(new FulfillmentCommandSelection.Operation(
                    "INBOUND",
                    product.getProductCode(),
                    null,
                    "Java random selection (batch target " + automaticLimit
                            + " BOX across " + participantCount
                            + " robots): an empty three-level rack slot is available."
            ));
        }
        Collections.shuffle(outboundBoxes, random);
        for (int index = 0; index < outboundCount; index++) {
            WarehouseItem item = outboundBoxes.get(index);
            operations.add(new FulfillmentCommandSelection.Operation(
                    "OUTBOUND",
                    item.getProduct().getProductCode(),
                    item.getId(),
                    "Java random selection (batch target " + automaticLimit
                            + " BOX across " + participantCount
                            + " robots): this unreserved physical BOX is available."
            ));
        }

        CommandExpressionMode expressionMode = request.selectCommandExpressionMode(
                seed ^ EXPRESSION_SALT
        );
        return new FulfillmentCommandSelection(
                seed,
                mode,
                inboundCount,
                outboundCount,
                expressionMode,
                List.copyOf(operations)
        );
    }

    private List<Product> filteredProducts(List<String> requestedCodes) {
        Set<String> filter = normalizedCodes(requestedCodes);
        return productRepository.findAllByOrderByProductCodeAsc().stream()
                .filter(product -> filter.isEmpty()
                        || filter.contains(normalize(product.getProductCode())))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private FulfillmentCommandMode resolveMode(
            FulfillmentCommandMode requested,
            boolean canInbound,
            boolean canOutbound,
            Random random
    ) {
        FulfillmentCommandMode effective = requested == null ? FulfillmentCommandMode.AUTO : requested;
        if (effective == FulfillmentCommandMode.INBOUND) {
            require(canInbound);
            return effective;
        }
        if (effective == FulfillmentCommandMode.OUTBOUND) {
            require(canOutbound);
            return effective;
        }
        if (effective == FulfillmentCommandMode.BOTH) {
            require(canInbound && canOutbound);
            return effective;
        }

        List<FulfillmentCommandMode> candidates = new ArrayList<>();
        if (canInbound) {
            candidates.add(FulfillmentCommandMode.INBOUND);
        }
        if (canOutbound) {
            candidates.add(FulfillmentCommandMode.OUTBOUND);
        }
        if (canInbound && canOutbound) {
            candidates.add(FulfillmentCommandMode.BOTH);
        }
        require(!candidates.isEmpty());
        return candidates.get(random.nextInt(candidates.size()));
    }

    private OperationCounts resolveCounts(
            FulfillmentCommandGenerateRequest request,
            FulfillmentCommandMode mode,
            int inboundAvailable,
            int outboundAvailable,
            int automaticLimit,
            Random random
    ) {
        if (mode == FulfillmentCommandMode.INBOUND) {
            return new OperationCounts(
                    resolveCount(request.inboundCount(), inboundAvailable, automaticLimit),
                    0
            );
        }
        if (mode == FulfillmentCommandMode.OUTBOUND) {
            return new OperationCounts(
                    0,
                    resolveCount(request.outboundCount(), outboundAvailable, automaticLimit)
            );
        }

        if (request.inboundCount() != null || request.outboundCount() != null) {
            int inbound = request.inboundCount() == null
                    ? Math.min(inboundAvailable, Math.max(1, automaticLimit / 2))
                    : resolveCount(request.inboundCount(), inboundAvailable, automaticLimit);
            int outbound = request.outboundCount() == null
                    ? Math.min(outboundAvailable, Math.max(1, automaticLimit - inbound))
                    : resolveCount(request.outboundCount(), outboundAvailable, automaticLimit);
            require(inbound > 0 && outbound > 0);
            return new OperationCounts(inbound, outbound);
        }

        int total = Math.min(automaticLimit, inboundAvailable + outboundAvailable);
        require(total >= 2);
        int inbound = total / 2;
        if (total % 2 != 0 && random.nextBoolean()) {
            inbound++;
        }
        int outbound = total - inbound;

        if (inbound > inboundAvailable) {
            outbound += inbound - inboundAvailable;
            inbound = inboundAvailable;
        }
        if (outbound > outboundAvailable) {
            inbound += outbound - outboundAvailable;
            outbound = outboundAvailable;
        }
        require(inbound > 0 && outbound > 0);
        return new OperationCounts(inbound, outbound);
    }

    private int resolveCount(Integer requested, int available, int automaticLimit) {
        if (requested != null) {
            require(requested > 0 && requested <= available);
            return requested;
        }
        int limit = Math.min(available, automaticLimit);
        require(limit > 0);
        return limit;
    }

    private int selectWorkloadMultiplier(Random random) {
        int[] weights = workloadMultiplierWeights();
        int totalWeight = 0;
        for (int weight : weights) {
            totalWeight += weight;
        }
        int draw = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int index = 0; index < weights.length; index++) {
            cumulative += weights[index];
            if (draw < cumulative) {
                return index + 1;
            }
        }
        return MAX_TASKS_PER_ROBOT;
    }

    private int[] workloadMultiplierWeights() {
        try {
            String[] values = workloadMultiplierWeights.split(",");
            if (values.length != MAX_TASKS_PER_ROBOT) {
                return DEFAULT_WORKLOAD_MULTIPLIER_WEIGHTS.clone();
            }
            int[] weights = new int[MAX_TASKS_PER_ROBOT];
            int total = 0;
            for (int index = 0; index < values.length; index++) {
                weights[index] = Integer.parseInt(values[index].trim());
                if (weights[index] < 0) {
                    return DEFAULT_WORKLOAD_MULTIPLIER_WEIGHTS.clone();
                }
                total += weights[index];
            }
            return total > 0 ? weights : DEFAULT_WORKLOAD_MULTIPLIER_WEIGHTS.clone();
        } catch (RuntimeException exception) {
            return DEFAULT_WORKLOAD_MULTIPLIER_WEIGHTS.clone();
        }
    }

    private Set<String> normalizedCodes(List<String> values) {
        return values == null
                ? Set.of()
                : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new BusinessException(ErrorCode.FULFILLMENT_COMMAND_NOT_GENERATED);
        }
    }

    private record OperationCounts(int inbound, int outbound) {}
}
