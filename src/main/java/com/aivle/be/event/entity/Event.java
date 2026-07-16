package com.aivle.be.event.entity;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.task.entity.Task;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "event")
@Getter @Builder
@AllArgsConstructor
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

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public static Event create(Warehouse warehouse, Robot robot, Task task,
                               EventType eventType, String description) {
        return Event.builder()
                .warehouse(warehouse)
                .robot(robot)
                .task(task)
                .eventType(eventType)
                .description(description)
                .occurredAt(LocalDateTime.now())
                .build();
    }

    public void resolve() {
        this.resolvedAt = LocalDateTime.now();
    }
}