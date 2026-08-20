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

    @Column(name = "release_at_seconds")
    private Integer releaseAtSeconds;

    @Column(name = "external_operation_id", length = 128)
    private String externalOperationId;

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

    @Column(name = "planned_completed_at")
    private LocalDateTime plannedCompletedAt;

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

    public void scheduleAt(Integer releaseAtSeconds) {
        this.releaseAtSeconds = releaseAtSeconds;
    }

    public void bindExternalOperationId(String externalOperationId) {
        this.externalOperationId = externalOperationId;
    }

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

    public void resetForReplay() {
        this.robot = null;
        this.status = TaskStatus.PENDING;
        this.assignedAt = null;
        this.startedAt = null;
        this.completedAt = null;
        this.plannedCompletedAt = null;
    }

    public void recordPlannedCompletion(LocalDateTime plannedCompletedAt) {
        this.plannedCompletedAt = plannedCompletedAt;
    }

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
