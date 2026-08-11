package com.aivle.be.simulationrun.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static lombok.AccessLevel.PROTECTED;

/**
 * 한 실행에서 AI가 만든 주기별 계획 요청과 응답을 진단 이력으로 보관한다.
 * 초기화하면 기존 이력은 삭제되고 새 실행 세대의 계획이 다시 기록된다.
 */
@Entity
@Table(
        name = "simulation_run_plan_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_simulation_run_plan_snapshot",
                columnNames = {"simulation_run_id", "cycle_minute"}
        )
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class SimulationRunPlanSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "simulation_run_id", nullable = false)
    private Long simulationRunId;

    /** 시뮬레이션 시각 기준 주기 번호. 0분 배치가 0이다. */
    @Column(name = "cycle_minute", nullable = false)
    private long cycleMinute;

    @Column(name = "request_json", nullable = false, columnDefinition = "text")
    private String requestJson;

    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static SimulationRunPlanSnapshot create(
            Long simulationRunId,
            long cycleMinute,
            String requestJson,
            String responseJson
    ) {
        SimulationRunPlanSnapshot snapshot = new SimulationRunPlanSnapshot();
        snapshot.simulationRunId = simulationRunId;
        snapshot.cycleMinute = cycleMinute;
        snapshot.requestJson = requestJson;
        snapshot.responseJson = responseJson;
        snapshot.createdAt = LocalDateTime.now();
        return snapshot;
    }
}
