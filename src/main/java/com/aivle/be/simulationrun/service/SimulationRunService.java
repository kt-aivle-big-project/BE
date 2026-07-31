package com.aivle.be.simulationrun.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.robotstate.controller.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.service.RobotStateValidationService;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.domain.ScenarioType;
import com.aivle.be.simulationrun.controller.request.InboundConfigRequest;
import com.aivle.be.simulationrun.controller.request.ScenarioConfigRequest;
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunHistoryResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.generation.ScenarioTaskPlanner;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SimulationRunService {

    private static final Set<SimulationRunStatus> ACTIVE_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED,
            SimulationRunStatus.REPLANNING
    );

    // 입고 품목 구성 비율의 합계
    private static final int TOTAL_RATIO = 100;

    // 런 자체의 생명주기(생성/시작/일시정지/재개/종료) 변경 브로드캐스트
    private static final String RUN_TOPIC = "/topic/simulation-runs";

    // 작업 상태 변경 브로드캐스트
    private static final String TASK_TOPIC = "/topic/tasks";

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final WarehouseRepository warehouseRepository;
    private final RobotRepository robotRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final RobotStateValidationService robotStateValidationService;
    private final ScenarioRepository scenarioRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskRepository taskRepository;
    private final SimulationPlaybackService simulationPlaybackService;
    private final ScenarioTaskPlanner scenarioTaskPlanner;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final GuestAccessPolicy guestAccessPolicy;

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public SimulationRunResponse create(SimulationRunCreateRequest request) {
        return create(request, (Long) null);
    }

    /**
     * 시뮬레이션 실행 생성.
     *
     * @param userId 실행한 사용자 ID (인증 정보에서 전달, 없으면 null)
     */
    @Transactional
    public SimulationRunResponse create(SimulationRunCreateRequest request, Long userId) {
        AuthenticatedRequester requester = userId == null
                ? null
                : AuthenticatedRequester.user(userId);
        return create(request, requester);
    }

    @Transactional
    public SimulationRunResponse create(
            SimulationRunCreateRequest request,
            AuthenticatedRequester requester
    ) {
        if (requester != null) {
            guestAccessPolicy.validateSimulationRunCreate(
                    requester,
                    request.warehouseId(),
                    request.scenarioId()
            );
        }
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

        // 시나리오 프리셋 + 실행 배속 적용
        Scenario preset = findScenarioOrNull(request.scenarioId(), warehouse.getId());
        run.applyScenario(preset, request.simulationSpeed());

        // 입고 품목 구성 비율 검증 (합계 100%)
        validateInboundRatio(request.inbound());

        // 실행자 기록 (내 실행 이력 조회용)
        if (requester != null && requester.isUser()) {
            run.assignUser(userRepository.getReferenceById(requester.userId()));
        } else if (requester != null && requester.isGuest()) {
            run.assignGuestSession(requester.guestSessionId());
        }

        // 작업을 만든 설정을 그대로 보관해 같은 설정으로 다시 실행할 수 있게 한다
        run.recordGenerationConfig(serializeGenerationConfig(request));

        SimulationRun saved = simulationRunRepository.save(run);

        // 입고/출고 설정을 실제 작업 목록으로 펼친다.
        // 전체 작업을 이 시점에 한 번에 만들어 두고,
        // 재생 엔진은 각 작업의 발생 시각(releaseAtSeconds)에 맞춰 투입한다.
        scenarioTaskPlanner.plan(
                saved.getId(),
                warehouse.getId(),
                request.inbound(),
                request.outbound(),
                scenario == null ? null : scenario.seed()
        );

        return broadcastRun(saved);
    }

    /**
     * 로그인한 사용자가 실행했던 시뮬레이션 이력을 최신순으로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<SimulationRunHistoryResponse> getMyRuns(Long userId) {
        return simulationRunRepository.findAllByUser_IdOrderByIdDesc(userId)
                .stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SimulationRunHistoryResponse> getMyRuns(AuthenticatedRequester requester) {
        List<SimulationRun> runs = requester.isUser()
                ? simulationRunRepository.findAllByUser_IdOrderByIdDesc(requester.userId())
                : simulationRunRepository.findAllByGuestSessionIdOrderByIdDesc(
                        requester.guestSessionId()
                );
        return runs.stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    /**
     * 시뮬레이션 초기화. 로봇 실시간 상태(Redis)를 비우고 대기 상태로 되돌린다.
     */
    @Transactional
    public SimulationRunResponse reset(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        run.reset();
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);

        // 같은 시나리오를 다시 처음부터 실행할 수 있도록 작업도 되돌린다
        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId);

        for (Task task : tasks) {
            task.resetForReplay();
            messagingTemplate.convertAndSend(TASK_TOPIC, new TaskResponse(task));
        }

        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse start(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        Long warehouseId = run.getWarehouse().getId();

        boolean alreadyActive = requester.isGuest()
                ? simulationRunRepository.existsByGuestSessionIdAndStatusInAndIdNot(
                        requester.guestSessionId(),
                        ACTIVE_STATUSES,
                        simulationRunId
                )
                : simulationRunRepository
                        .existsByWarehouse_IdAndGuestSessionIdIsNullAndStatusInAndIdNot(
                                warehouseId,
                                ACTIVE_STATUSES,
                                simulationRunId
                        );
        if (alreadyActive) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_ALREADY_ACTIVE);
        }

        List<Robot> availableRobots =
                robotRepository.findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                        warehouseId,
                        RobotAvailabilityStatus.AVAILABLE
                );
        int robotCount = run.getRobotCount() == null
                ? availableRobots.size()
                : Math.max(0, run.getRobotCount());
        List<Robot> robots = availableRobots.stream()
                .limit(robotCount)
                .toList();
        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_AVAILABLE_ROBOTS);
        }

        LocalDateTime now = LocalDateTime.now();
        run.start(now);

        // 초기화 후 재시작하는 경우 참가 기록이 이미 있으므로 중복 등록을 피한다
        List<SimulationRunRobot> participants = robots.stream()
                .filter(robot -> !simulationRunRobotRepository
                        .existsBySimulationRun_IdAndRobot_Id(simulationRunId, robot.getId()))
                .map(robot -> SimulationRunRobot.create(run, robot))
                .toList();

        if (!participants.isEmpty()) {
            simulationRunRobotRepository.saveAll(participants);
        }

        robots.stream()
                .map(robot -> initialState(robot, warehouseId, now))
                .forEach(state -> {
                    simulationRunStateStore.save(simulationRunId, state);
                    messagingTemplate.convertAndSend(robotTopic(simulationRunId), RobotStateResponse.from(state));
                });

        // 대기 중인 작업을 로봇에게 배정하고 이동 계획을 만든다.
        // 이후 스케줄러가 계획을 한 단계씩 재생한다.
        simulationPlaybackService.buildPlan(simulationRunId, robots);

        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse pause(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        run.pause(LocalDateTime.now());
        return broadcastRun(run);
    }

    /**
     * 실행 배속 변경.
     *
     * 실행 기록을 갱신하고, 재생 중이면 엔진의 시계 속도도 즉시 바꾼다.
     * 정지 상태에서 바꿔두면 다음 시작 때 그 배속으로 재생된다.
     */
    @Transactional
    public SimulationRunResponse changeSpeed(
            Long simulationRunId,
            SimulationSpeedUpdateRequest request,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        run.changeSpeed(request.simulationSpeed());

        simulationPlaybackService.changeSpeed(
                simulationRunId, request.simulationSpeed());

        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse resume(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        run.resume();
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse stop(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        run.stop(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        return broadcastRun(run);
    }

    /**
     * 창고에서 진행 중인 모든 시뮬레이션을 중지한다.
     *
     * 한 창고에서는 하나의 실행만 활성화될 수 있으므로,
     * 새 시뮬레이션을 만들기 전에 이전 실행을 정리하는 용도로 쓴다.
     *
     * @return 중지된 실행 수
     */
    @Transactional
    public int stopActiveRuns(Long warehouseId) {
        List<SimulationRun> activeRuns = simulationRunRepository
                .findAllByWarehouse_IdAndStatusIn(warehouseId, ACTIVE_STATUSES);

        LocalDateTime now = LocalDateTime.now();

        for (SimulationRun run : activeRuns) {
            run.stop(now);
            simulationRunStateStore.deleteAll(run.getId());
            simulationPlaybackService.clear(run.getId());
            broadcastRun(run);
        }

        return activeRuns.size();
    }

    @Transactional
    public SimulationRunResponse complete(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.complete(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse fail(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.fail(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional(readOnly = true)
    public SimulationRunResponse getStatus(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        return SimulationRunResponse.from(findOwnedBy(simulationRunId, requester));
    }

    @Transactional(readOnly = true)
    public SimulationRunParticipantsResponse getParticipants(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        findOwnedBy(simulationRunId, requester);
        List<Long> robotIds = simulationRunRobotRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(simulationRunId)
                .stream()
                .map(participant -> participant.getRobot().getId())
                .toList();
        return new SimulationRunParticipantsResponse(simulationRunId, robotIds);
    }

    @Transactional(readOnly = true)
    public SimulationRunRobotStatesResponse getRobotStates(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        List<RobotStateResponse> states = simulationRunStateStore.findAll(simulationRunId)
                .stream()
                .map(RobotStateResponse::from)
                .toList();
        return new SimulationRunRobotStatesResponse(
                simulationRunId,
                run.getStatus(),
                states,
                simulationPlaybackService.currentClockMillis(simulationRunId)
        );
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

    private SimulationRun findOwnedBy(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findById(simulationRunId);
        boolean owned = requester.isUser()
                ? run.isOwnedByUser(requester.userId())
                : run.isOwnedByGuest(requester.guestSessionId());
        if (!owned) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return run;
    }

    private SimulationRunHistoryResponse toHistoryResponse(SimulationRun run) {
        return SimulationRunHistoryResponse.of(
                run,
                taskRepository.countBySimulationRun_Id(run.getId())
        );
    }

    private RobotState initialState(Robot robot, Long warehouseId, LocalDateTime now) {
        String nodeCode = robot.getNodeId() == null
                ? null
                : warehouseNodeRepository.findById(robot.getNodeId())
                .map(WarehouseNode::getNodeCode)
                .orElse(null);

        return RobotState.stationary(
                robot.getId(),
                warehouseId,
                robot.getNodeId(),
                nodeCode,
                robot.getBattery(),
                RobotStatus.IDLE,
                null,
                now
        );
    }

    private Scenario findScenarioOrNull(Long scenarioId, Long warehouseId) {
        if (scenarioId == null) {
            return null;
        }
        Scenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SCENARIO_NOT_FOUND));
        if (!scenario.getWarehouse().getId().equals(warehouseId)) {
            throw new BusinessException(ErrorCode.SCENARIO_WAREHOUSE_MISMATCH);
        }
        return scenario;
    }

    private void validateInboundRatio(InboundConfigRequest inbound) {
        if (inbound == null || inbound.products() == null || inbound.products().isEmpty()) {
            return;
        }
        if (inbound.ratioTotal() != TOTAL_RATIO) {
            throw new BusinessException(ErrorCode.INVALID_INBOUND_RATIO);
        }
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
    /**
     * 작업 생성에 쓰인 입출고 설정을 JSON 문자열로 만든다.
     * 직렬화에 실패해도 실행 생성 자체를 막지는 않는다.
     */
    private String serializeGenerationConfig(SimulationRunCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "inbound", request.inbound() == null ? Map.of() : request.inbound(),
                    "outbound", request.outbound() == null ? Map.of() : request.outbound()
            ));
        } catch (Exception exception) {
            log.warn("생성 설정 직렬화 실패: {}", exception.getMessage());
            return null;
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
