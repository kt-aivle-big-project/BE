package com.aivle.be.simulationrun.entity;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "simulation_run_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

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

    @Version
    private Long version;

    public static SimulationRun create(Warehouse warehouse, LocalDateTime now) {
        return create(warehouse, now, ScenarioType.MANUAL, null, null, null, null);
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

    public void resume() {
        requireStatus(SimulationRunStatus.PAUSED);
        status = SimulationRunStatus.RUNNING;
    }

    public void stop(LocalDateTime now) {
        if (status != SimulationRunStatus.CREATED
                && status != SimulationRunStatus.RUNNING
                && status != SimulationRunStatus.PAUSED) {
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
        if (status != SimulationRunStatus.RUNNING && status != SimulationRunStatus.PAUSED) {
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
