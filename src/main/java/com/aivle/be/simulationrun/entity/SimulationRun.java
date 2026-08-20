package com.aivle.be.simulationrun.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.user.entity.User;
import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "simulation_runs")
@Getter
@NoArgsConstructor(access = PROTECTED)
public class SimulationRun {

    private static final int DEFAULT_COMMAND_GENERATION_INTERVAL_SECONDS = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "simulation_run_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_session_id", length = 36)
    private String guestSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SimulationRunStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "scenario_type", length = 20)
    private ScenarioType scenarioType;

    @Column(name = "random_seed")
    private Long randomSeed;

    @Column(name = "planned_task_count")
    private Integer plannedTaskCount;

    @Column(name = "inbound_ratio")
    private Double inboundRatio;

    @Column(name = "generation_interval_seconds")
    private Integer generationIntervalSeconds;

    @Column(
            name = "execution_version",
            nullable = false,
            columnDefinition = "bigint default 1"
    )
    private long executionVersion = 1L;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id")
    private Scenario scenario;

    @Column(name = "simulation_speed")
    private Double simulationSpeed;

    @Column(name = "robot_count")
    private Integer robotCount;

    @Column(name = "charging_threshold")
    private Integer chargingThreshold;

    @Column(name = "initial_battery")
    private Integer initialBattery;

    @Column(name = "auto_replan")
    private Boolean autoReplan;

    @Column(name = "obstacle_enabled")
    private Boolean obstacleEnabled;

    @Column(name = "generation_config", columnDefinition = "text")
    private String generationConfig;

    @Version
    private Long version;

    public static SimulationRun create(Warehouse warehouse, LocalDateTime now) {
        return create(warehouse, now, ScenarioType.MANUAL, null, null, null, null);
    }

    public static SimulationRun createRolling(Warehouse warehouse, LocalDateTime now) {
        return create(
                warehouse,
                now,
                ScenarioType.MANUAL,
                null,
                null,
                null,
                DEFAULT_COMMAND_GENERATION_INTERVAL_SECONDS
        );
    }

    public void enableRollingCommandGeneration() {
        generationIntervalSeconds = DEFAULT_COMMAND_GENERATION_INTERVAL_SECONDS;
    }

    public static SimulationRun create(
            Warehouse warehouse,
            LocalDateTime now,
            ScenarioType scenarioType,
            Long randomSeed,
            Integer plannedTaskCount,
            Double inboundRatio,
            Integer generationIntervalSeconds
    ) {
        SimulationRun run = new SimulationRun();
        run.warehouse = warehouse;
        run.status = SimulationRunStatus.CREATED;
        run.createdAt = now;
        run.scenarioType = scenarioType == null ? ScenarioType.MANUAL : scenarioType;
        run.randomSeed = randomSeed;
        run.plannedTaskCount = plannedTaskCount;
        run.inboundRatio = inboundRatio;
        run.generationIntervalSeconds = generationIntervalSeconds;
        return run;
    }

    public void assignUser(User user) {
        this.user = user;
        this.guestSessionId = null;
    }

    public void assignGuestSession(String guestSessionId) {
        this.user = null;
        this.guestSessionId = guestSessionId;
    }

    public boolean isOwnedByUser(Long userId) {
        return userId != null
                && user != null
                && userId.equals(user.getId());
    }

    public boolean isOwnedByGuest(String guestSessionId) {
        return guestSessionId != null
                && guestSessionId.equals(this.guestSessionId);
    }

    public void recordGenerationConfig(String generationConfig) {
        this.generationConfig = generationConfig;
    }

    public void applyScenario(Scenario scenario, Double simulationSpeed) {
        this.scenario = scenario;
        if (scenario != null) {
            this.chargingThreshold = scenario.getChargingThreshold();
            this.autoReplan = scenario.getAutoReplan();
            this.obstacleEnabled = scenario.getObstacleEnabled();
            this.simulationSpeed = scenario.getSimulationSpeed();
            this.initialBattery = scenario.getInitialBattery();
        }
        if (simulationSpeed != null) {
            this.simulationSpeed = simulationSpeed;
        }
        if (this.simulationSpeed == null) {
            this.simulationSpeed = 1.0;
        }
    }

    public void recordRobotCount(int robotCount) {
        this.robotCount = robotCount;
    }

    public void changeSpeed(Double simulationSpeed) {
        if (simulationSpeed == null || simulationSpeed <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SIMULATION_SPEED);
        }
        this.simulationSpeed = simulationSpeed;
    }

    public void start(LocalDateTime now) {
        requireStatus(SimulationRunStatus.CREATED);
        status = SimulationRunStatus.RUNNING;
        startedAt = now;
    }

    public void pause(LocalDateTime now) {
        requireStatus(SimulationRunStatus.RUNNING);
        status = SimulationRunStatus.PAUSED;
        pausedAt = now;
    }

    public void pauseForHumanReview(LocalDateTime now) {
        if (status != SimulationRunStatus.RUNNING
                && status != SimulationRunStatus.QUIESCING
                && status != SimulationRunStatus.REPLANNING
                && status != SimulationRunStatus.PENDING_ACTIVATION) {
            throw new BusinessException(ErrorCode.INVALID_SIMULATION_RUN_TRANSITION);
        }
        status = SimulationRunStatus.PAUSED;
        pausedAt = now;
    }

    public void resume() {
        requireStatus(SimulationRunStatus.PAUSED);
        status = SimulationRunStatus.RUNNING;
    }

    public void startQuiescing() {
        requireStatus(SimulationRunStatus.RUNNING);
        status = SimulationRunStatus.QUIESCING;
    }

    public void startReplanning() {
        requireStatus(SimulationRunStatus.QUIESCING);
        status = SimulationRunStatus.REPLANNING;
    }

    public void waitForPlanActivation() {
        requireStatus(SimulationRunStatus.REPLANNING);
        status = SimulationRunStatus.PENDING_ACTIVATION;
    }

    /**
     * 재계획 종료 후 실행 상태로 복귀.
     */
    public void finishReplanning() {
        if (status != SimulationRunStatus.REPLANNING
                && status != SimulationRunStatus.PENDING_ACTIVATION
                && status != SimulationRunStatus.QUIESCING) {
            throw invalidTransition();
        }
        status = SimulationRunStatus.RUNNING;
    }

    public void reset() {
        executionVersion++;
        status = SimulationRunStatus.CREATED;
        startedAt = null;
        pausedAt = null;
        endedAt = null;
    }

    public void stop(LocalDateTime now) {
        if (status != SimulationRunStatus.CREATED
                && status != SimulationRunStatus.RUNNING
                && status != SimulationRunStatus.PAUSED
                && status != SimulationRunStatus.QUIESCING
                && status != SimulationRunStatus.REPLANNING
                && status != SimulationRunStatus.PENDING_ACTIVATION) {
            throw invalidTransition();
        }
        status = SimulationRunStatus.STOPPED;
        endedAt = now;
    }

    public void complete(LocalDateTime now) {
        requireStatus(SimulationRunStatus.RUNNING);
        status = SimulationRunStatus.COMPLETED;
        endedAt = now;
    }

    public void fail(LocalDateTime now) {
        if (status != SimulationRunStatus.RUNNING
                && status != SimulationRunStatus.PAUSED
                && status != SimulationRunStatus.QUIESCING
                && status != SimulationRunStatus.REPLANNING
                && status != SimulationRunStatus.PENDING_ACTIVATION) {
            throw invalidTransition();
        }
        status = SimulationRunStatus.FAILED;
        endedAt = now;
    }

    private void requireStatus(SimulationRunStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private BusinessException invalidTransition() {
        return new BusinessException(ErrorCode.INVALID_SIMULATION_RUN_TRANSITION);
    }
}
