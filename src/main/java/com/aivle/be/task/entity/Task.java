package com.aivle.be.task.entity;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "task")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    // 할당 전에는 비어있음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id")
    private Robot robot;

    // 입출고 작업일 때만 사용 (어떤 재고/LOT에 대한 작업인지)
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

    public enum TaskType {
        INBOUND, OUTBOUND, MOVE, CHARGE
    }

    public enum TaskStatus {
        PENDING, ASSIGNED, IN_PROGRESS, DONE, FAILED, CANCELLED
    }
}