package com.aivle.be.task.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
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
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "task")
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

    /** AI 주문 계약과 연결되는 내부 식별자. 사용자에게 직접 입력받지 않는다. */
    @Column(name = "order_id", length = 100)
    private String orderId;

    @Column(name = "operation_id", length = 100)
    private String operationId;

    @Column(name = "inbound_id", length = 100)
    private String inboundId;

    @Column(name = "operation_type", length = 30)
    private String operationType;

    @Column(name = "handling_unit_id", length = 100)
    private String handlingUnitId;

    @Column(name = "priority", length = 20)
    private String priority;

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

    public void attachAiContract(
            String operationId,
            String orderId,
            String inboundId,
            String operationType,
            String handlingUnitId,
            String priority
    ) {
        if (operationId == null || operationId.isBlank()
                || operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("AI operation contract is required.");
        }
        if (taskType == TaskType.OUTBOUND && (orderId == null || orderId.isBlank())) {
            throw new IllegalArgumentException("Outbound task requires order_id.");
        }
        if (taskType == TaskType.INBOUND && (inboundId == null || inboundId.isBlank())) {
            throw new IllegalArgumentException("Inbound task requires inbound_id.");
        }
        this.operationId = operationId;
        this.orderId = orderId;
        this.inboundId = inboundId;
        this.operationType = operationType;
        this.handlingUnitId = handlingUnitId;
        this.priority = priority;
    }

    /**
     * 시뮬레이션 내 작업 발생 시각을 지정한다. (시나리오 타임라인용)
     */
    public void scheduleAt(Integer releaseAtSeconds) {
        this.releaseAtSeconds = releaseAtSeconds;
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

    /**
     * Installs the assignment selected by a validated external plan.
     * A solver may queue multiple tasks for the same robot, so this transition
     * intentionally does not apply the interactive assignment availability rule.
     */
    public void assignFromValidatedPlan(Robot robot) {
        if (this.status != TaskStatus.PENDING) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.robot = robot;
        this.status = TaskStatus.ASSIGNED;
        this.assignedAt = LocalDateTime.now();
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
