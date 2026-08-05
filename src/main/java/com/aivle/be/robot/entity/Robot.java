package com.aivle.be.robot.entity;

import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "robot")
@Getter
@Setter
@NoArgsConstructor
public class Robot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "robot_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_spec_id", nullable = false)
    private RobotSpec robotSpec;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    // 참고: node_id / battery / status는 실시간으로 계속 바뀌는 값이라
    // 원래는 Redis에서 관리하기로 했습니다. 여기 남겨둔 필드는
    // "최초 등록 시 초기값" 또는 "참고용 스냅샷" 정도로만 쓰시고,
    // 실제 실시간 조회/갱신은 Redis 쪽 로직을 쓰시는 걸 추천드립니다.
    // Live position is stored in Redis. This persistent node is the robot's
    // stable initial/home charging slot and becomes its cuOpt end node.
    @Column(name = "node_id")
    private Long nodeId;

    @Column(nullable = false)
    private Integer battery;

    @Convert(converter = RobotAvailabilityStatusConverter.class)
    @Column(nullable = false)
    private RobotAvailabilityStatus status;

    public static Robot create(
            RobotSpec robotSpec,
            Warehouse warehouse,
            Long nodeId,
            Integer battery,
            RobotAvailabilityStatus status
    ) {
        Robot robot = new Robot();
        robot.robotSpec = robotSpec;
        robot.warehouse = warehouse;
        robot.nodeId = nodeId;
        robot.battery = battery;
        robot.status = status == null ? RobotAvailabilityStatus.AVAILABLE : status;
        return robot;
    }

    public void update(
            RobotSpec robotSpec,
            Warehouse warehouse,
            Long nodeId,
            Integer battery,
            RobotAvailabilityStatus status
    ) {
        this.robotSpec = robotSpec;
        this.warehouse = warehouse;
        this.nodeId = nodeId;
        this.battery = battery;
        if (status != null) {
            this.status = status;
        }
    }
}
