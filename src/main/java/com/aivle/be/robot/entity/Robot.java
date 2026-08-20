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

    // Live position is stored in Redis. This persistent node is the robot's
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
