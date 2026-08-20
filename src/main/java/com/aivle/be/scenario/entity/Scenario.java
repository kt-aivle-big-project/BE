package com.aivle.be.scenario.entity;

import com.aivle.be.scenario.domain.ScenarioStatus;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(name = "scenario_name", nullable = false, length = 100)
    private String scenarioName;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "robot_count", nullable = false)
    private Integer robotCount;

    // 시뮬레이션 시작 시 로봇 배터리 초기값(%)
    //
    @Column(name = "initial_battery", columnDefinition = "integer default 100")
    private Integer initialBattery;

    @Column(name = "simulation_speed", nullable = false)
    private Double simulationSpeed;

    @Column(name = "charging_threshold", nullable = false)
    private Integer chargingThreshold;

    @Column(name = "auto_replan", nullable = false)
    private Boolean autoReplan;

    @Column(name = "obstacle_enabled", nullable = false)
    private Boolean obstacleEnabled;


    // 노드 한 칸 이동에 걸리는 시간
    @Column(name = "move_seconds_per_node")
    private Double moveSecondsPerNode;

    // 집품(픽업)에 걸리는 시간
    @Column(name = "picking_seconds")
    private Double pickingSeconds;

    // 적재/하역에 걸리는 시간
    @Column(name = "loading_seconds")
    private Double loadingSeconds;

    // ===== 목록 화면용 =====

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, columnDefinition = "varchar(20) default 'DRAFT'")
    private ScenarioStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = ScenarioStatus.DRAFT;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static Scenario create(
            Warehouse warehouse,
            String scenarioCode,
            String scenarioName,
            String description,
            Integer robotCount,
            Integer initialBattery,
            Double simulationSpeed,
            Integer chargingThreshold,
            Boolean autoReplan,
            Boolean obstacleEnabled
    ) {
        Scenario scenario = new Scenario();
        scenario.warehouse = warehouse;
        scenario.scenarioCode = scenarioCode;
        scenario.scenarioName = scenarioName;
        scenario.description = description;
        scenario.robotCount = robotCount;
        scenario.initialBattery = initialBattery == null ? 100 : initialBattery;
        scenario.simulationSpeed = simulationSpeed == null ? 1.0 : simulationSpeed;
        scenario.chargingThreshold = chargingThreshold;
        scenario.autoReplan = autoReplan != null && autoReplan;
        scenario.obstacleEnabled = obstacleEnabled != null && obstacleEnabled;
        scenario.status = ScenarioStatus.DRAFT;
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

    public void updateSettings(
            String scenarioName,
            String description,
            Integer robotCount,
            Integer initialBattery,
            Double simulationSpeed,
            Integer chargingThreshold,
            Boolean autoReplan,
            Boolean obstacleEnabled,
            ScenarioStatus status
    ) {
        if (status != null) this.status = status;
        if (scenarioName != null) this.scenarioName = scenarioName;
        if (description != null) this.description = description;
        if (robotCount != null) this.robotCount = robotCount;
        if (initialBattery != null) this.initialBattery = initialBattery;
        if (simulationSpeed != null) this.simulationSpeed = simulationSpeed;
        if (chargingThreshold != null) this.chargingThreshold = chargingThreshold;
        if (autoReplan != null) this.autoReplan = autoReplan;
        if (obstacleEnabled != null) this.obstacleEnabled = obstacleEnabled;
    }
}
