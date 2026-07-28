package com.aivle.be.scenario.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(
        name = "scenario",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_scenario_warehouse_code",
                columnNames = {"warehouse_id", "scenario_code"}
        )
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "scenario_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    // 프론트 scenario_id ("S1")
    @Column(name = "scenario_code", nullable = false, length = 50)
    private String scenarioCode;

    // 프론트 scenario_name ("시나리오 v1")
    @Column(name = "scenario_name", nullable = false, length = 100)
    private String scenarioName;

    @Column(name = "robot_count", nullable = false)
    private Integer robotCount;

    @Column(name = "simulation_speed", nullable = false)
    private Double simulationSpeed;

    @Column(name = "charging_threshold", nullable = false)
    private Integer chargingThreshold;

    @Column(name = "auto_replan", nullable = false)
    private Boolean autoReplan;

    @Column(name = "obstacle_enabled", nullable = false)
    private Boolean obstacleEnabled;

    // ===== 작업 수행 시간 (시뮬레이션 초 단위) =====

    // 노드 한 칸 이동에 걸리는 시간
    @Column(name = "move_seconds_per_node")
    private Double moveSecondsPerNode;

    // 집품(픽업)에 걸리는 시간
    @Column(name = "picking_seconds")
    private Double pickingSeconds;

    // 적재/하역에 걸리는 시간
    @Column(name = "loading_seconds")
    private Double loadingSeconds;

    public static Scenario create(
            Warehouse warehouse,
            String scenarioCode,
            String scenarioName,
            Integer robotCount,
            Double simulationSpeed,
            Integer chargingThreshold,
            Boolean autoReplan,
            Boolean obstacleEnabled
    ) {
        Scenario scenario = new Scenario();
        scenario.warehouse = warehouse;
        scenario.scenarioCode = scenarioCode;
        scenario.scenarioName = scenarioName;
        scenario.robotCount = robotCount;
        scenario.simulationSpeed = simulationSpeed == null ? 1.0 : simulationSpeed;
        scenario.chargingThreshold = chargingThreshold;
        scenario.autoReplan = autoReplan != null && autoReplan;
        scenario.obstacleEnabled = obstacleEnabled != null && obstacleEnabled;
        scenario.moveSecondsPerNode = 2.0;
        scenario.pickingSeconds = 5.0;
        scenario.loadingSeconds = 5.0;
        return scenario;
    }

    /**
     * 작업 수행 시간 설정.
     */
    public void updateTimings(
            Double moveSecondsPerNode,
            Double pickingSeconds,
            Double loadingSeconds
    ) {
        if (moveSecondsPerNode != null) {
            this.moveSecondsPerNode = moveSecondsPerNode;
        }
        if (pickingSeconds != null) {
            this.pickingSeconds = pickingSeconds;
        }
        if (loadingSeconds != null) {
            this.loadingSeconds = loadingSeconds;
        }
    }

    // SimulationSetting 화면의 "설정 저장"에 대응
    public void updateSettings(
            String scenarioName,
            Integer robotCount,
            Double simulationSpeed,
            Integer chargingThreshold,
            Boolean autoReplan,
            Boolean obstacleEnabled
    ) {
        if (scenarioName != null) this.scenarioName = scenarioName;
        if (robotCount != null) this.robotCount = robotCount;
        if (simulationSpeed != null) this.simulationSpeed = simulationSpeed;
        if (chargingThreshold != null) this.chargingThreshold = chargingThreshold;
        if (autoReplan != null) this.autoReplan = autoReplan;
        if (obstacleEnabled != null) this.obstacleEnabled = obstacleEnabled;
    }
}
