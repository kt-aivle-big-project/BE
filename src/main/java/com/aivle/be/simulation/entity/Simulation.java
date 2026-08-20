package com.aivle.be.simulation.entity;

import com.aivle.be.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(name = "simulation")
@Getter
@NoArgsConstructor(access = PROTECTED)
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "path_nodes", columnDefinition = "jsonb")
    private List<Long> pathNodes;

    @Lob
    @Column(name = "agent_input")
    private String agentInput;

    @Lob
    @Column(name = "agent_output")
    private String agentOutput;

    private Integer tokens;

    private Long latency;

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

    public Simulation(Warehouse warehouse, Long missionId, Long robotId,
                      Long startNode, Long endNode, String taskCode) {
        this.warehouse = warehouse;
        this.missionId = missionId;
        this.robotId = robotId;
        this.startNode = startNode;
        this.endNode = endNode;
        this.taskCode = taskCode;
        this.executedAt = LocalDateTime.now();
    }

    public void updatePath(List<Long> pathNodes) {
        this.pathNodes = pathNodes;
    }

    // AI 에이전트 호출 결과를 나중에 기록
    public void recordAgentInteraction(String agentInput, String agentOutput,
                                       Integer tokens, Long latency, String toolCallId) {
        this.agentInput = agentInput;
        this.agentOutput = agentOutput;
        this.tokens = tokens;
        this.latency = latency;
        this.toolCallId = toolCallId;
    }

    // 규칙/정책 판정 결과 기록
    public void recordPolicyResult(String ruleCode, String policyResult) {
        this.ruleCode = ruleCode;
        this.policyResult = policyResult;
    }

    public void complete(boolean success) {
        this.success = success;
        this.completedAt = LocalDateTime.now();
    }
}