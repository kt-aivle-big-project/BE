package com.aivle.be.scenario.repository;

import com.aivle.be.scenario.entity.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {

    List<Scenario> findAllByWarehouse_IdOrderByIdAsc(Long warehouseId);

    Optional<Scenario> findByWarehouse_IdAndScenarioCode(Long warehouseId, String scenarioCode);

    boolean existsByWarehouse_IdAndScenarioCode(Long warehouseId, String scenarioCode);
}