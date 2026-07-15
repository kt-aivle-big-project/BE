package com.aivle.be.event.entity;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.task.entity.Task;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "event")
@Getter
@Setter
@NoArgsConstructor
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

    // 이 이벤트가 어떤 작업 도중 발생했는지 (없을 수도 있음)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tadocker exec -it warehouse-postgres psql -U warehouse -d warehouse -c \"\\dt\"sk_id")
    private Task task;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @Lob
    private String description;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    // 재계산/조치로 해소된 시각. 미해결이면 null
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public enum EventType {
        COLLISION_RISK, PATH_BLOCKED, LOW_BATTERY, TASK_FAILED, REPLAN_TRIGGERED
    }
}