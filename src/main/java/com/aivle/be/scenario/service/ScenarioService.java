package com.aivle.be.scenario.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.scenario.controller.request.ScenarioRequest;
import com.aivle.be.scenario.controller.request.ScenarioUpdateRequest;
import com.aivle.be.scenario.controller.response.ScenarioResponse;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public ScenarioResponse create(ScenarioRequest request) {
        Warehouse warehouse = findWarehouse(request.warehouseId());
        if (scenarioRepository.existsByWarehouse_IdAndScenarioCode(
                warehouse.getId(), request.scenarioCode())) {
            throw new BusinessException(ErrorCode.DUPLICATE_SCENARIO_CODE);
        }

        Scenario scenario = Scenario.create(
                warehouse,
                request.scenarioCode().trim(),
                request.scenarioName().trim(),
                request.robotCount(),
                request.simulationSpeed(),
                request.chargingThreshold(),
                request.autoReplan(),
                request.obstacleEnabled()
        );
        return ScenarioResponse.from(scenarioRepository.save(scenario));
    }

    public ScenarioResponse get(Long scenarioId) {
        return ScenarioResponse.from(findById(scenarioId));
    }

    public List<ScenarioResponse> getAll(Long warehouseId) {
        if (warehouseId == null) {
            return scenarioRepository.findAll().stream()
                    .map(ScenarioResponse::from)
                    .toList();
        }
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
        }
        return scenarioRepository.findAllByWarehouse_IdOrderByIdAsc(warehouseId).stream()
                .map(ScenarioResponse::from)
                .toList();
    }

    @Transactional
    public ScenarioResponse update(Long scenarioId, ScenarioUpdateRequest request) {
        Scenario scenario = findById(scenarioId);
        scenario.updateSettings(
                request.scenarioName(),
                request.robotCount(),
                request.simulationSpeed(),
                request.chargingThreshold(),
                request.autoReplan(),
                request.obstacleEnabled()
        );
        return ScenarioResponse.from(scenario);
    }

    @Transactional
    public void delete(Long scenarioId) {
        scenarioRepository.delete(findById(scenarioId));
    }

    private Scenario findById(Long scenarioId) {
        return scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCENARIO_NOT_FOUND));
    }

    private Warehouse findWarehouse(Long warehouseId) {
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
    }
}
