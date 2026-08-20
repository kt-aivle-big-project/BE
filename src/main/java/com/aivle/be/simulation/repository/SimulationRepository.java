package com.aivle.be.simulation.repository;

import com.aivle.be.simulation.entity.Simulation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {

    @Query(value = """
            SELECT * FROM simulation
            WHERE completed_at IS NULL
              AND path_nodes @> to_jsonb(:nodeId)
            """, nativeQuery = true)
    List<Simulation> findRunningSimulationsContainingNode(@Param("nodeId") Long nodeId);
}