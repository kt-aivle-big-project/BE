package com.aivle.be.simulationrun.entity;

import com.aivle.be.robot.entity.Robot;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Entity
@Table(
        name = "simulation_run_robots",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_simulation_run_robot",
                columnNames = {"simulation_run_id", "robot_id"}
        )
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class SimulationRunRobot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_run_id", nullable = false)
    private SimulationRun simulationRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    public static SimulationRunRobot create(SimulationRun simulationRun, Robot robot) {
        SimulationRunRobot participant = new SimulationRunRobot();
        participant.simulationRun = simulationRun;
        participant.robot = robot;
        return participant;
    }
}
