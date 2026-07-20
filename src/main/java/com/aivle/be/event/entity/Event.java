package com.aivle.be.event.entity;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.task.entity.Task;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Event {

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
    @JoinColumn(name = "task_id")
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Lob
    private String description;

    // 장애물/차단이 발생한 노드 위치. COLLISION_RISK, PATH_BLOCKED류에서 경로 겹침 판단에 사용. 그 외 이벤트는 null 가능
    @Column(name = "node_id")
    private Long nodeId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public Event(Warehouse warehouse, Robot robot, Task task, EventType eventType, String description, Long nodeId) {
        this.warehouse = warehouse;
        this.robot = robot;
        this.task = task;
        this.eventType = eventType;
        this.description = description;
        this.nodeId = nodeId;
        this.occurredAt = LocalDateTime.now();
    }

    public void resolve() {
        this.resolvedAt = LocalDateTime.now();
    }
}