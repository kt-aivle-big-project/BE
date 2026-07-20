package com.aivle.be.simulation.repository;

import com.aivle.be.simulation.entity.Simulation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {
}