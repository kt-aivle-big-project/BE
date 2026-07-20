package com.aivle.be.simulation.repository;

import com.aivle.be.simulation.entity.Simulation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {

    // 완료되지 않은(진행중인) 시뮬레이션 중, path_nodes 배열에 nodeId가 포함된 것만 조회
    // JPQL로는 Postgres JSONB 연산자(@>)를 못 써서 native query로 처리
    @Query(value = """
            SELECT * FROM simulation
            WHERE completed_at IS NULL
              AND path_nodes @> to_jsonb(:nodeId)
            """, nativeQuery = true)
    List<Simulation> findRunningSimulationsContainingNode(@Param("nodeId") Long nodeId);
}