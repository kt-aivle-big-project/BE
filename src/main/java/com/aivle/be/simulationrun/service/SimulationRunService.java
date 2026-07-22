package com.aivle.be.simulationrun.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.robotstate.dto.response.RobotStateResponse;
import com.aivle.be.robotstate.dto.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.service.RobotStateValidationService;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.simulationrun.dto.request.ScenarioConfigRequest;
import com.aivle.be.simulationrun.dto.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.dto.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.dto.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.dto.response.SimulationRunResponse;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SimulationRunService {

    private static final Set<SimulationRunStatus> ACTIVE_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED
    );

    // 런 자체의 생명주기(생성/시작/일시정지/재개/종료) 변경 브로드캐스트
    private static final String RUN_TOPIC = "/topic/simulation-runs";

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final RobotStateValidationService robotStateValidationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public SimulationRunResponse create(SimulationRunCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        ScenarioConfigRequest scenario = request.scenario();
        ScenarioType scenarioType = scenario == null || scenario.type() == null
                ? ScenarioType.MANUAL
                : scenario.type();
        validateScenario(scenarioType, scenario);
        SimulationRun run = SimulationRun.create(
                warehouse,
                LocalDateTime.now(),
                scenarioType,
                scenario == null ? null : scenario.seed(),
                scenario == null ? null : scenario.taskCount(),
                scenario == null ? null : scenario.inboundRatio(),
                scenario == null || scenario.generationIntervalSeconds() == null
                        ? 0
                        : scenario.generationIntervalSeconds()
        );
      
        return broadcastRun(simulationRunRepository.save(run));
    }

    @Transactional
    public SimulationRunResponse start(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        Long warehouseId = run.getWarehouse().getId();

        if (simulationRunRepository.existsByWarehouse_IdAndStatusInAndIdNot(
                warehouseId,
                ACTIVE_STATUSES,
                simulationRunId
        )) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_ALREADY_ACTIVE);
        }

        List<Robot> robots = robotRepository.findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                warehouseId,
                RobotAvailabilityStatus.AVAILABLE
        );
        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_AVAILABLE_ROBOTS);
        }

        LocalDateTime now = LocalDateTime.now();
        run.start(now);
        List<SimulationRunRobot> participants = robots.stream()
                .map(robot -> SimulationRunRobot.create(run, robot))
                .toList();
        simulationRunRobotRepository.saveAll(participants);

        robots.stream()
                .map(robot -> initialState(robot, warehouseId, now))
                .forEach(state -> {
                    simulationRunStateStore.save(simulationRunId, state);
                    messagingTemplate.convertAndSend(robotTopic(simulationRunId), RobotStateResponse.from(state));
                });

        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse pause(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.pause(LocalDateTime.now());
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse resume(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.resume();
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse stop(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.stop(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse complete(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.complete(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse fail(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.fail(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional(readOnly = true)
    public SimulationRunResponse getStatus(Long simulationRunId) {
        return SimulationRunResponse.from(findById(simulationRunId));
    }

    @Transactional(readOnly = true)
    public SimulationRunParticipantsResponse getParticipants(Long simulationRunId) {
        findById(simulationRunId);
        List<Long> robotIds = simulationRunRobotRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(simulationRunId)
                .stream()
                .map(participant -> participant.getRobot().getId())
                .toList();
        return new SimulationRunParticipantsResponse(simulationRunId, robotIds);
    }

    @Transactional(readOnly = true)
    public SimulationRunRobotStatesResponse getRobotStates(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        List<RobotStateResponse> states = simulationRunStateStore.findAll(simulationRunId)
                .stream()
                .map(RobotStateResponse::from)
                .toList();
        return new SimulationRunRobotStatesResponse(simulationRunId, run.getStatus(), states);
    }

    @Transactional
    public RobotStateResponse updateRobotState(
            Long simulationRunId,
            Long robotId,
            RobotStateUpdateRequest request
    ) {
        SimulationRun run = findById(simulationRunId);
        if (run.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }
        if (!simulationRunRobotRepository.existsBySimulationRun_IdAndRobot_Id(
                simulationRunId,
                robotId
        )) {
            throw new BusinessException(ErrorCode.ROBOT_NOT_IN_SIMULATION_RUN);
        }

        RobotState currentState = simulationRunStateStore
                .findByRobotId(simulationRunId, robotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_STATE_NOT_FOUND));
        RobotState nextState = robotStateValidationService.validate(
                robotId,
                request,
                Optional.of(currentState)
        );

        RobotStateResponse response = RobotStateResponse.from(simulationRunStateStore.save(simulationRunId, nextState));
        messagingTemplate.convertAndSend(robotTopic(simulationRunId), response);
        return response;
    }

    private SimulationRun findById(Long simulationRunId) {
        return simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
    }

    private RobotState initialState(Robot robot, Long warehouseId, LocalDateTime now) {
        return new RobotState(
                robot.getId(),
                warehouseId,
                robot.getNodeId(),
                robot.getBattery(),
                RobotStatus.IDLE,
                null,
                now
        );
    }

    private void validateScenario(ScenarioType type, ScenarioConfigRequest scenario) {
        if (type != ScenarioType.RANDOM) {
            return;
        }
        if (scenario == null
                || scenario.seed() == null
                || scenario.taskCount() == null
                || scenario.inboundRatio() == null) {
            throw new BusinessException(ErrorCode.INVALID_SCENARIO_CONFIG);
        }
    }
    private SimulationRunResponse broadcastRun(SimulationRun run) {
        SimulationRunResponse response = SimulationRunResponse.from(run);
        messagingTemplate.convertAndSend(RUN_TOPIC, response);
        return response;
    }

    private String robotTopic(Long simulationRunId) {
        return RUN_TOPIC + "/" + simulationRunId + "/robots";
    }
}
