package com.aivle.be.simulationrun.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.service.LaroInventoryReservationService;
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
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunHistoryResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunLowBatteryEventResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.commandcycle.SimulationRunPlanSnapshotStore;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.repository.UserRepository;
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
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SimulationRunService {

    private static final Set<SimulationRunStatus> ACTIVE_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED,
            SimulationRunStatus.QUIESCING,
            SimulationRunStatus.REPLANNING,
            SimulationRunStatus.PENDING_ACTIVATION
    );

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
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskRepository taskRepository;
    private final SimulationPlaybackService simulationPlaybackService;
    private final SimulationCommandCycleService simulationCommandCycleService;
    private final SimulationRunPlanSnapshotStore simulationRunPlanSnapshotStore;
    private final LaroInventoryReservationService inventoryReservationService;
    private final UserRepository userRepository;
    private final ScenarioRepository scenarioRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final GuestAccessPolicy guestAccessPolicy;

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunService.class);

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
        validateWarehouseForExecution(warehouse, requester);
        SimulationRun run = SimulationRun.createRolling(warehouse, LocalDateTime.now());

        // 화면에서 고른 시나리오의 설정(충전 기준·자동 재계획·장애물·배속)을 실행에 옮긴다.
        // 시나리오를 안 골랐으면 예전처럼 요청 배속만 쓴다.
        Scenario scenario = findScenarioForWarehouse(
                request.scenarioId(), warehouse.getId());

        run.applyScenario(scenario, request.simulationSpeed());

        // 실행자 기록 (내 실행 이력 조회용)
        if (requester != null && requester.isUser()) {
            run.assignUser(userRepository.getReferenceById(requester.userId()));
        } else if (requester != null && requester.isGuest()) {
            run.assignGuestSession(requester.guestSessionId());
        }

        SimulationRun saved = simulationRunRepository.save(run);

        return broadcastRun(saved);
    }

    /**
     * 실행에 쓸 시나리오를 찾는다.
     *
     * <p>고르지 않았으면 null 이다. 다른 창고의 시나리오는 설비·노드가 달라
     * 그대로 쓸 수 없으므로 거부한다.
     */
    private Scenario findScenarioForWarehouse(Long scenarioId, Long warehouseId) {
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
        SimulationRun run = findOwnedByForUpdate(simulationRunId, requester);
        run.reset();
        // 초기화 뒤에는 현재 실행 ID를 재사용하지 않는다. 같은 실행 ID에서 새 명령을
        // 만들면 기존 external operation ID와 충돌할 수 있으므로 다음 시작은 새 실행으로 한다.
        run.stop(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        simulationCommandCycleService.stop(simulationRunId);
        simulationRunPlanSnapshotStore.deleteAll(simulationRunId);
        inventoryReservationService.releaseActiveForRun(simulationRunId);

        // 초기화는 현재 재고를 그대로 유지하면서 실행 중이던 화면/작업만 정리한다.
        // 이미 랙에 반영된 입·출고를 PENDING 으로 되돌리면 같은 작업이 재실행되어
        // 재고가 이중 반영되므로, 미완료 작업은 취소하고 다음 시작 때 새 배치를 만든다.
        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId);

        for (Task task : tasks) {
            if (task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.ASSIGNED
                    || task.getStatus() == TaskStatus.IN_PROGRESS) {
                task.cancel();
            }
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
        validateWarehouseForExecution(run.getWarehouse(), requester);
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

        // 창고에 등록된 로봇을 전부 투입한다.
        //
        // 예전에는 시나리오 프리셋의 robot_count 만큼 잘라서 썼는데,
        // 창고에 로봇을 추가해도 화면에 안 나타나 혼란스러웠다.
        // 투입 대수는 "창고에 로봇을 몇 대 등록했는가"로 정한다.
        List<Robot> robots =
                robotRepository.findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                        warehouseId,
                        RobotAvailabilityStatus.AVAILABLE
                );

        if (robots.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_AVAILABLE_ROBOTS);
        }

        // 실제 참가 대수를 기록해 둔다 (실행 이력 조회용)
        run.recordRobotCount(robots.size());

        log.info("[실행] runId={} 창고 {} 로봇 {}대 투입", simulationRunId, warehouseId, robots.size());

        LocalDateTime now = LocalDateTime.now();
        run.enableRollingCommandGeneration();
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
                .map(robot -> initialState(robot, warehouseId, now, run.getInitialBattery()))
                .forEach(state -> {
                    simulationRunStateStore.save(simulationRunId, state);
                    messagingTemplate.convertAndSend(robotTopic(simulationRunId), RobotStateResponse.from(state));
                });

        // 기존 일괄 작업/BFS 재생 대신 커밋 후 0분 명령 생성을 시작한다.
        // 이후 시뮬레이션 시각 5분, 10분 ...마다 같은 파이프라인이 반복된다.
        simulationCommandCycleService.startAfterCommit(simulationRunId);

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
        simulationCommandCycleService.stop(simulationRunId);
        inventoryReservationService.releaseActiveForRun(simulationRunId);
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
    public int stopActiveRuns(
            Long warehouseId,
            AuthenticatedRequester requester
    ) {
        List<SimulationRun> activeRuns = simulationRunRepository
                .findAllByWarehouse_IdAndStatusIn(
                        warehouseId,
                        ACTIVE_STATUSES
                );

        if (requester.isGuest()) {
            activeRuns = activeRuns.stream()
                    .filter(run -> run.isOwnedByGuest(
                            requester.guestSessionId()
                    ))
                    .toList();
        }

        LocalDateTime now = LocalDateTime.now();

        for (SimulationRun run : activeRuns) {
            run.stop(now);
            simulationRunStateStore.deleteAll(run.getId());
            simulationPlaybackService.clear(run.getId());
            simulationCommandCycleService.stop(run.getId());
            inventoryReservationService.releaseActiveForRun(run.getId());
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
        simulationCommandCycleService.stop(simulationRunId);
        inventoryReservationService.releaseActiveForRun(simulationRunId);
        return broadcastRun(run);
    }

    @Transactional
    public SimulationRunResponse fail(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.fail(LocalDateTime.now());
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        simulationCommandCycleService.stop(simulationRunId);
        inventoryReservationService.releaseActiveForRun(simulationRunId);
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
                run.getExecutionVersion(),
                run.getStatus(),
                states,
                simulationPlaybackService.currentClockMillis(simulationRunId)
        );
    }

    /**
     * 작업 중인 AI 로봇 한 대의 playback 배터리를 20%로 낮춘다.
     *
     * Redis만 수정하면 다음 playback tick이 이전 값을 다시 저장하므로,
     * 반드시 playback의 권위 상태를 먼저 변경하고 그 상태를 발행한다.
     */
    public SimulationRunLowBatteryEventResponse injectLowBatteryEvent(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        if (run.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }

        return SimulationRunLowBatteryEventResponse.from(
                simulationPlaybackService.injectRandomActiveRobotLowBattery(
                        simulationRunId,
                        20
                )
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

    @Transactional(readOnly = true)
    public void validateOwnership(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = findOwnedBy(simulationRunId, requester);
        validateWarehouseForExecution(run.getWarehouse(), requester);
    }

    private void validateWarehouseForExecution(
            Warehouse warehouse,
            AuthenticatedRequester requester
    ) {
        if (warehouse.isShared()) {
            throw new BusinessException(
                    ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
            );
        }
        if (requester != null
                && requester.isUser()
                && !warehouse.isOwnedBy(requester.userId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (requester != null
                && requester.isGuest()
                && !warehouse.isOwnedByGuest(requester.guestSessionId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
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

    private SimulationRun findOwnedByForUpdate(
            Long simulationRunId,
            AuthenticatedRequester requester
    ) {
        SimulationRun run = simulationRunRepository.findByIdForUpdate(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
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

    /**
     * 시작 시점의 로봇 상태를 만든다.
     *
     * <p>배터리는 시나리오의 초기 배터리를 쓴다. 시나리오를 안 골랐으면
     * 로봇에 등록된 값을 그대로 쓴다.
     */
    private RobotState initialState(
            Robot robot,
            Long warehouseId,
            LocalDateTime now,
            Integer initialBattery
    ) {
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
                initialBattery == null ? robot.getBattery() : initialBattery,
                RobotStatus.IDLE,
                null,
                now
        );
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
