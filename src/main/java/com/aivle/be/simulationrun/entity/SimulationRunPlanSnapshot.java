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
 * 한 실행에서 AI가 만들어 준 계획을 그대로 보관한다.
 *
 * <p>초기화 후 다시 시작하면 이 스냅샷을 읽어 <b>AI를 거치지 않고</b> 같은 계획을
 * 다시 실행한다. 그래서 "시작 → 초기화 → 시작"이 언제나 같은 시나리오가 된다.
 *
 * <p>주기(0분, 5분, 10분 ...)마다 계획이 하나씩 나오므로 주기 번호까지 키에 넣는다.
 * 재생할 때도 같은 순서로 꺼내 쓴다.
 *
 * <p>요청과 응답을 함께 저장하는 이유: 응답을 실행에 반영하는
 * {@code LaroPlanExecutionService.prepareIfReady} 가 둘 다 필요하다.
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
