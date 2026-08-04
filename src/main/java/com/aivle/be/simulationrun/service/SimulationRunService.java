package com.aivle.be.simulationrun.service;

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
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunParticipantsResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunRobotStatesResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunHistoryResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
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
    private final LaroInventoryReservationService inventoryReservationService;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunService.class);

    @Transactional
    public SimulationRunResponse create(SimulationRunCreateRequest request) {
        return create(request, null);
    }

    /**
     * 시뮬레이션 실행 생성.
     *
     * @param userId 실행한 사용자 ID (인증 정보에서 전달, 없으면 null)
     */
    @Transactional
    public SimulationRunResponse create(SimulationRunCreateRequest request, Long userId) {
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        SimulationRun run = SimulationRun.createRolling(warehouse, LocalDateTime.now());

        run.applyScenario(null, request.simulationSpeed());

        // 실행자 기록 (내 실행 이력 조회용)
        if (userId != null) {
            run.assignUser(userRepository.getReferenceById(userId));
        }

        SimulationRun saved = simulationRunRepository.save(run);

        return broadcastRun(saved);
    }

    /**
     * 로그인한 사용자가 실행했던 시뮬레이션 이력을 최신순으로 반환한다.
     */
    @Transactional(readOnly = true)
    public List<SimulationRunHistoryResponse> getMyRuns(Long userId) {
        return simulationRunRepository.findAllByUser_IdOrderByIdDesc(userId)
                .stream()
                .map(run -> SimulationRunHistoryResponse.of(
                        run,
                        taskRepository.countBySimulationRun_Id(run.getId())
                ))
                .toList();
    }

    /**
     * 시뮬레이션 초기화. 로봇 실시간 상태(Redis)를 비우고 대기 상태로 되돌린다.
     */
    @Transactional
    public SimulationRunResponse reset(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
        run.reset();
        simulationRunStateStore.deleteAll(simulationRunId);
        simulationPlaybackService.clear(simulationRunId);
        simulationCommandCycleService.stop(simulationRunId);
        inventoryReservationService.releaseActiveForRun(simulationRunId);

        // rolling-horizon 실행은 재시작할 때 새 0분 배치를 만든다.
        // 이전 배치의 미완료 작업은 재생하지 않고 취소한다.
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
                .map(robot -> initialState(robot, warehouseId, now))
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
    public SimulationRunResponse pause(Long simulationRunId) {
        SimulationRun run = findById(simulationRunId);
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
            SimulationSpeedUpdateRequest request
    ) {
        SimulationRun run = findById(simulationRunId);
        run.changeSpeed(request.simulationSpeed());

        simulationPlaybackService.changeSpeed(
                simulationRunId, request.simulationSpeed());

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
    public int stopActiveRuns(Long warehouseId) {
        List<SimulationRun> activeRuns = simulationRunRepository
                .findAllByWarehouse_IdAndStatusIn(warehouseId, ACTIVE_STATUSES);

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

    private SimulationRunResponse broadcastRun(SimulationRun run) {
        SimulationRunResponse response = SimulationRunResponse.from(run);
        messagingTemplate.convertAndSend(RUN_TOPIC, response);
        return response;
    }

    private String robotTopic(Long simulationRunId) {
        return RUN_TOPIC + "/" + simulationRunId + "/robots";
    }
}
