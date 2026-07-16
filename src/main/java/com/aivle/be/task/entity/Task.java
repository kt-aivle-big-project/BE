package com.aivle.be.task.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
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
    @JoinColumn(name = "warehouse_item_id")
    private WarehouseItem warehouseItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_node_id", nullable = false)
    private WarehouseNode startNode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "end_node_id", nullable = false)
    private WarehouseNode endNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false)
    private TaskType taskType;

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
        this.warehouse = warehouse;
        this.startNode = startNode;
        this.endNode = endNode;
        this.taskType = taskType;
        this.warehouseItem = warehouseItem;
        this.status = TaskStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
    }

    public void assignRobot(Robot robot) {
        if (this.status != TaskStatus.PENDING) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_PROCESSED);
        }
        this.robot = robot;
        this.status = TaskStatus.ASSIGNED;
        this.assignedAt = LocalDateTime.now();
    }

    public void start() {
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = TaskStatus.DONE;
        this.completedAt = LocalDateTime.now();
    }

    public void fail() {
        this.status = TaskStatus.FAILED;
    }

    public void cancel() {
        this.status = TaskStatus.CANCELLED;
    }
}