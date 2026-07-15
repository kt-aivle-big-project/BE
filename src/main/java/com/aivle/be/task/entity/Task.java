package com.aivle.be.task.entity;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "task")
@Getter @Builder
@AllArgsConstructor
@NoArgsConstructor(access = PROTECTED)
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

    // 입출고 작업일 때만 사용
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

    // 생성 전용 팩토리 - Builder를 직접 노출하지 않고, 항상 PENDING/requestedAt=now로 시작하도록 강제
    public static Task create(Warehouse warehouse, WarehouseNode startNode, WarehouseNode endNode,
                              TaskType taskType, WarehouseItem warehouseItem) {
        return Task.builder()
                .warehouse(warehouse)
                .startNode(startNode)
                .endNode(endNode)
                .taskType(taskType)
                .warehouseItem(warehouseItem)
                .status(TaskStatus.PENDING)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    // 상태 변경은 setter 대신 의미 있는 메서드로 - 검증 로직도 여기 같이 둠
    public void assignRobot(Robot robot) {
        if (this.status != TaskStatus.PENDING) {
            throw new IllegalStateException("이미 처리 중이거나 종료된 작업입니다. 현재 상태: " + this.status);
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