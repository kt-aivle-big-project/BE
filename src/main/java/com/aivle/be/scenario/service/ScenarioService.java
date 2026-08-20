package com.aivle.be.scenario.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.repository.RobotRepository;
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
    private final RobotRepository robotRepository;

    /** 화면이 안 보내는 값에 쓰는 기본값 */
    private static final double DEFAULT_SIMULATION_SPEED = 1.0;
    private static final int DEFAULT_INITIAL_BATTERY = 100;
    private static final int DEFAULT_CHARGING_THRESHOLD = 20;

    @Transactional
    public ScenarioResponse create(
            ScenarioRequest request,
            AuthenticatedRequester requester
    ) {
        Warehouse warehouse = findWarehouse(request.warehouseId());
        validateCreateAccess(warehouse, requester);

        int availableRobotCount = robotRepository
                .findAllByWarehouse_Id(warehouse.getId())
                .size();
        if (availableRobotCount == 0) {
            throw new BusinessException(ErrorCode.NO_AVAILABLE_ROBOTS);
        }

        int robotCount = request.robotCount() == null
                ? availableRobotCount
                : Math.min(request.robotCount(), availableRobotCount);

        String scenarioCode = resolveScenarioCode(
                warehouse.getId(), request.scenarioCode());

        Scenario scenario = Scenario.create(
                warehouse,
                scenarioCode,
                request.scenarioName().trim(),
                request.description() == null ? null : request.description().trim(),
                robotCount,
                request.initialBattery() == null
                        ? DEFAULT_INITIAL_BATTERY : request.initialBattery(),
                request.simulationSpeed() == null
                        ? DEFAULT_SIMULATION_SPEED : request.simulationSpeed(),
                request.chargingThreshold() == null
                        ? DEFAULT_CHARGING_THRESHOLD : request.chargingThreshold(),
                request.autoReplan() == null || request.autoReplan(),
                request.obstacleEnabled() != null && request.obstacleEnabled()
        );
        return ScenarioResponse.from(scenarioRepository.save(scenario));
    }

    private void validateCreateAccess(
            Warehouse warehouse,
            AuthenticatedRequester requester
    ) {
        if (warehouse.isShared()) {
            throw new BusinessException(ErrorCode.SHARED_WAREHOUSE_READ_ONLY);
        }

        boolean ownedByRequester = requester.isUser()
                ? warehouse.isOwnedBy(requester.userId())
                : warehouse.isOwnedByGuest(requester.guestSessionId());
        if (!ownedByRequester) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private String resolveScenarioCode(Long warehouseId, String requestedCode) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            String code = requestedCode.trim();
            if (scenarioRepository.existsByWarehouse_IdAndScenarioCode(warehouseId, code)) {
                throw new BusinessException(ErrorCode.DUPLICATE_SCENARIO_CODE);
            }
            return code;
        }

        for (int number = 1; number <= 1000; number++) {
            String candidate = "S" + number;
            if (!scenarioRepository.existsByWarehouse_IdAndScenarioCode(warehouseId, candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.DUPLICATE_SCENARIO_CODE);
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
                request.description(),
                request.robotCount(),
                request.initialBattery(),
                request.simulationSpeed(),
                request.chargingThreshold(),
                request.autoReplan(),
                request.obstacleEnabled(),
                request.status()
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
