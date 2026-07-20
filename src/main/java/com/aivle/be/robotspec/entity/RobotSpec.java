package com.aivle.be.robotspec.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "robots")
@Getter @Setter
@NoArgsConstructor
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
}