package com.aivle.be.simulation.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "simulation_run")
@Getter
@Setter
@NoArgsConstructor
public class Simulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "simulation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "mission_id")
    private Long missionId;

    @Column(name = "robot_id")
    private Long robotId;

    @Column(name = "start_node")
    private Long startNode;

    @Column(name = "end_node")
    private Long endNode;

    @Column(name = "task_code")
    private String taskCode;

    // LLM 에이전트 입출력은 길어질 수 있어서 @Lob(TEXT) 처리
    @Lob
    @Column(name = "agent_input")
    private String agentInput;

    @Lob
    @Column(name = "agent_output")
    private String agentOutput;

    private Integer tokens;

    private Long latency; // 단위: ms 권장

    @Column(name = "tool_call_id")
    private String toolCallId;

    private Boolean success;

    @Column(name = "rule_code")
    private String ruleCode;

    @Column(name = "policy_result")
    private String policyResult;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}