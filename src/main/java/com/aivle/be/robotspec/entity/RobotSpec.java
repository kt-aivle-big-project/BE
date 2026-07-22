package com.aivle.be.robotspec.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "robot_specs")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class RobotSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로봇 모델 코드 - 비즈니스상 의미있는 값이지만 PK는 아님 (인조키로 통일)
    @Column(name = "robot_code", nullable = false, unique = true)
    private String robotCode;

    @Column(name = "task_code")
    private String taskCode;

    @Column(name = "base_battery_rate", nullable = false)
    private Double baseBatteryRate;

    @Column(name = "work_battery_rate", nullable = false)
    private Double workBatteryRate;

    @Column(name = "failure_rate")
    private Double failureRate;

    public static RobotSpec create(
            String robotCode,
            String taskCode,
            Double baseBatteryRate,
            Double workBatteryRate,
            Double failureRate
    ) {
        RobotSpec spec = new RobotSpec();
        spec.robotCode = robotCode;
        spec.taskCode = taskCode;
        spec.baseBatteryRate = baseBatteryRate;
        spec.workBatteryRate = workBatteryRate;
        spec.failureRate = failureRate;
        return spec;
    }

    public void update(
            String robotCode,
            String taskCode,
            Double baseBatteryRate,
            Double workBatteryRate,
            Double failureRate
    ) {
        this.robotCode = robotCode;
        this.taskCode = taskCode;
        this.baseBatteryRate = baseBatteryRate;
        this.workBatteryRate = workBatteryRate;
        this.failureRate = failureRate;
    }
}
