package com.aivle.be.task.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(
        name = "task",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_run_external_operation",
                columnNames = {"simulation_run_id", "external_operation_id"}
        )
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id")
    private Robot robot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_run_id")
    private SimulationRun simulationRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_item_id")
    private WarehouseItem warehouseItem;

    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_node_id", nullable = false)
    private WarehouseNode startNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "end_node_id", nullable = false)
    private WarehouseNode endNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private TaskType taskType;

    @Column(name = "quantity")
    private Integer quantity;

    // 시뮬레이션 시작 후 몇 초에 이 작업이 발생하는지 (null이면 시작과 동시에)
    @Column(name = "release_at_seconds")
    private Integer releaseAtSeconds;

    @Column(name = "external_operation_id", length = 128)
    private String externalOperationId;

    /** AI가 입고 계획에서 선택한 실제 선반 층. null은 레거시 자동 배정을 뜻한다. */
    @Column(name = "target_rack_level")
    private Integer targetRackLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * AI 계획상 이 작업이 끝나기로 되어 있던 시각.
     *
     * <p>계획 응답의 마지막 단계 {@code end_at_ms} 를 실행 시작 시각에 더해 저장한다.
     * 마감 시각(SLA)이 아니라 "이대로 가면 언제 끝나는가"이며,
     * 실제 완료 시각과 비교해 지연을 계산한다.
     *
     * <p>계획을 받지 못한 작업(수동 생성 등)은 null 이고 지연도 계산하지 않는다.
     */
    @Column(name = "planned_completed_at")
    private LocalDateTime plannedCompletedAt;

    /**
     * Physical rack inventory is changed when the relevant rack service ends,
     * not when the whole route eventually becomes DONE. This timestamp keeps
     * the inventory side effect idempotent across playback ticks and fallbacks.
     */
    @Column(name = "inventory_applied_at")
    private LocalDateTime inventoryAppliedAt;

    public Task(Warehouse warehouse, WarehouseNode startNode, WarehouseNode endNode,
                TaskType taskType, WarehouseItem warehouseItem) {
        this(warehouse, startNode, endNode, taskType, warehouseItem, null, null, null);
    }

    public Task(Warehouse warehouse, WarehouseNode startNode, WarehouseNode endNode,
                TaskType taskType, WarehouseItem warehouseItem, SimulationRun simulationRun) {
        this(warehouse, startNode, endNode, taskType, warehouseItem, simulationRun, null, null);
    }

    public Task(Warehouse warehouse, WarehouseNode startNode, WarehouseNode endNode,
                TaskType taskType, WarehouseItem warehouseItem, SimulationRun simulationRun, Integer quantity) {
        this(warehouse, startNode, endNode, taskType, warehouseItem, simulationRun, quantity, null);
    }

    public Task(Warehouse warehouse, WarehouseNode startNode, WarehouseNode endNode,
                TaskType taskType, WarehouseItem warehouseItem, SimulationRun simulationRun, Integer quantity, Long itemId) {
        this.warehouse = warehouse;
        this.startNode = startNode;
        this.endNode = endNode;
        this.taskType = taskType;
        this.warehouseItem = warehouseItem;
        this.simulationRun = simulationRun;
        this.quantity = quantity;
        this.itemId = itemId != null ? itemId : (warehouseItem != null ? warehouseItem.getItemId() : null);
        this.status = TaskStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
    }

    public Long getEffectiveItemId() {
        return itemId != null ? itemId : (warehouseItem != null ? warehouseItem.getItemId() : null);
    }

    public int effectiveQuantity() {
        return quantity == null ? 1 : quantity;
    }

    public int effectiveReleaseAtSeconds() {
        return releaseAtSeconds == null ? 0 : releaseAtSeconds;
    }

    /**
     * 시뮬레이션 내 작업 발생 시각을 지정한다. (시나리오 타임라인용)
     */
    public void scheduleAt(Integer releaseAtSeconds) {
        this.releaseAtSeconds = releaseAtSeconds;
    }

    /** AI operation_id와 BE 작업을 재시도 가능한 형태로 연결한다. */
    public void bindExternalOperationId(String externalOperationId) {
        this.externalOperationId = externalOperationId;
    }

    /**
     * 입고 계획이 선택한 선반 층을 실행 작업에 고정한다.
     * 같은 operation의 재시도는 같은 층만 허용한다.
     */
    public void reserveTargetRackLevel(Integer targetRackLevel) {
        if (targetRackLevel == null) {
            return;
        }
        validateInboundRackLevel(targetRackLevel);
        if (this.targetRackLevel != null && !this.targetRackLevel.equals(targetRackLevel)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        this.targetRackLevel = targetRackLevel;
    }

    /**
     * Binds the physical putaway destination selected by the AI plan.
     * Route/access nodes belong to the executable timeline and must never be
     * stored as the business destination of an inbound Task.
     */
    public void planInboundDestination(WarehouseNode rackNode, Integer rackLevel) {
        validateInboundRackNode(rackNode);
        if (isInventoryApplied()) {
            if (!rackNode.getId().equals(endNode.getId())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
            reserveTargetRackLevel(rackLevel);
            return;
        }
        this.endNode = rackNode;
        reserveTargetRackLevel(rackLevel);
    }

    /**
     * Replaces an inbound putaway destination while a rolling-horizon replan is
     * still free to move the physical work. Once execution or inventory
     * mutation starts, the original rack contract remains immutable.
     */
    public void replanInboundDestination(WarehouseNode rackNode, Integer rackLevel) {
        validateInboundRackNode(rackNode);
        if (isInventoryApplied()
                || (status != TaskStatus.PENDING && status != TaskStatus.ASSIGNED)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (rackLevel != null) {
            validateInboundRackLevel(rackLevel);
        }
        this.endNode = rackNode;
        this.targetRackLevel = rackLevel;
    }

    private void validateInboundRackNode(WarehouseNode rackNode) {
        if (taskType != TaskType.INBOUND
                || rackNode == null
                || rackNode.getNodeType() != NodeType.RACK_STORAGE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (warehouse == null
                || rackNode.getWarehouse() == null
                || !warehouse.getId().equals(rackNode.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateInboundRackLevel(Integer rackLevel) {
        if (taskType != TaskType.INBOUND || rackLevel < 1 || rackLevel > 3) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    /**
     * 시뮬레이션 초기화 시 작업을 처음 상태로 되돌린다.
     * 같은 시나리오를 반복 실행할 수 있게 한다.
     */
    public void resetForReplay() {
        this.robot = null;
        this.status = TaskStatus.PENDING;
        this.assignedAt = null;
        this.startedAt = null;
        this.completedAt = null;
        this.plannedCompletedAt = null;
    }

    /**
     * AI 계획상 이 작업의 예정 종료 시각을 기록한다.
     *
     * <p>계획을 받은 직후에 호출한다.
     * 재계획으로 예정 시각이 바뀌면 다시 호출해 덮어쓴다.
     */
    public void recordPlannedCompletion(LocalDateTime plannedCompletedAt) {
        this.plannedCompletedAt = plannedCompletedAt;
    }

    /**
     * 계획보다 얼마나 늦게 끝났는지(분).
     *
     * <p>계획이 없거나 아직 안 끝났으면 null.
     * 계획보다 빨리 끝났으면 0 으로 본다.
     */
    public Long delayMinutes() {
        if (plannedCompletedAt == null || completedAt == null) {
            return null;
        }

        long minutes = java.time.Duration
                .between(plannedCompletedAt, completedAt)
                .toMinutes();

        return Math.max(0, minutes);
    }

    public void assignRobot(Robot robot) {
        if (this.status != TaskStatus.PENDING) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.robot = robot;
        this.status = TaskStatus.ASSIGNED;
        this.assignedAt = LocalDateTime.now();
    }

    public void reassignRobot(Robot robot) {
        if (this.status != TaskStatus.ASSIGNED
                && this.status != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(
                    ErrorCode.TASK_ALREADY_PROCESSED
            );
        }

        if (this.robot != null
                && this.robot.getId().equals(robot.getId())) {
            return;
        }

        this.robot = robot;
        this.status = TaskStatus.ASSIGNED;
        this.assignedAt = LocalDateTime.now();
        this.startedAt = null;
    }

    public void start() {
        if (this.status != TaskStatus.ASSIGNED) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        if (this.status != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.status = TaskStatus.DONE;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isInventoryApplied() {
        return inventoryAppliedAt != null;
    }

    public void markInventoryApplied() {
        if (inventoryAppliedAt == null) {
            inventoryAppliedAt = LocalDateTime.now();
        }
    }

    public void fail() {
        if (this.status != TaskStatus.ASSIGNED && this.status != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.status = TaskStatus.FAILED;
    }

    public void cancel() {
        if (this.status == TaskStatus.DONE
                || this.status == TaskStatus.FAILED
                || this.status == TaskStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.status = TaskStatus.CANCELLED;
    }
}
