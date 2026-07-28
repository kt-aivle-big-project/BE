package com.aivle.be.simulationrun.playback;

import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.controller.response.RobotStateResponse;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.controller.response.TaskResponse;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시뮬레이션 재생 엔진 (시간 기반).
 *
 * 내부 시계를 두고, 작업은 지정된 발생 시각(releaseAtSeconds)에 투입된다.
 * 유휴 로봇이 대기 중인 작업을 집어가고, 이동/집품/적재에 각각 소요 시간이 걸린다.
 *
 * 지금은 백엔드가 BFS로 경로를 계산하지만,
 * cuOpt 연동 후에는 AI가 만든 경로를 그대로 사용하도록 교체하면 된다.
 */
@Service
@RequiredArgsConstructor
public class SimulationPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(SimulationPlaybackService.class);

    private static final String RUN_TOPIC = "/topic/simulation-runs";
    private static final String TASK_TOPIC = "/topic/tasks";

    // 계획 대상 작업 상태
    private static final List<TaskStatus> PLANNABLE_STATUSES =
            List.of(TaskStatus.PENDING, TaskStatus.ASSIGNED);

    // 이동 1칸당 배터리 소모(%)
    private static final double BATTERY_PER_MOVE = 0.4;
    private static final double BATTERY_PER_WORK = 1.0;

    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;
    private final TaskRepository taskRepository;
    private final RobotRepository robotRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskService taskService;
    private final WarehousePathFinder pathFinder;
    private final SimpMessagingTemplate messagingTemplate;

    // 진행 중인 재생 (simulationRunId -> 상태)
    private final Map<Long, PlaybackContext> contexts = new ConcurrentHashMap<>();

    // 노드 코드 캐시 (nodeId -> nodeCode)
    private final Map<Long, String> nodeCodeCache = new ConcurrentHashMap<>();

    /* =========================================================
       계획 수립
    ========================================================= */

    /**
     * 시뮬레이션 시작 시 호출.
     * 작업을 발생 시각 순으로 예약하고 로봇 실행 상태를 초기화한다.
     */
    @Transactional
    public void buildPlan(Long simulationRunId, List<Robot> robots) {
        SimulationRun run = simulationRunRepository.findById(simulationRunId).orElse(null);
        if (run == null || robots.isEmpty()) {
            return;
        }

        Long warehouseId = run.getWarehouse().getId();

        List<Task> tasks = taskRepository
                .findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(
                        simulationRunId, PLANNABLE_STATUSES);

        if (tasks.isEmpty()) {
            log.info("[재생] runId={} 실행할 작업이 없습니다.", simulationRunId);
            return;
        }

        cacheNodeCodes(warehouseId);

        // 발생 시각 순으로 예약
        List<PlaybackContext.ScheduledTask> scheduled = tasks.stream()
                .map(task -> new PlaybackContext.ScheduledTask(
                        task.getId(), task.effectiveReleaseAtSeconds()))
                .sorted(Comparator.comparingInt(PlaybackContext.ScheduledTask::releaseAtSeconds))
                .toList();

        int initialBattery = run.getInitialBattery() == null ? 100 : run.getInitialBattery();

        List<RobotRuntime> runtimes = robots.stream()
                .map(robot -> new RobotRuntime(robot.getId(), robot.getNodeId(), initialBattery))
                .toList();

        Scenario scenario = run.getScenario();
        double moveSeconds = scenario == null || scenario.getMoveSecondsPerNode() == null
                ? 2.0 : scenario.getMoveSecondsPerNode();
        double pickingSeconds = scenario == null || scenario.getPickingSeconds() == null
                ? 5.0 : scenario.getPickingSeconds();
        double loadingSeconds = scenario == null || scenario.getLoadingSeconds() == null
                ? 5.0 : scenario.getLoadingSeconds();

        double speed = run.getSimulationSpeed() == null ? 1.0 : run.getSimulationSpeed();

        PlaybackContext context = new PlaybackContext(
                simulationRunId,
                warehouseId,
                pathFinder.loadAdjacency(warehouseId),
                new ArrayList<>(runtimes),
                scheduled,
                speed,
                moveSeconds,
                pickingSeconds,
                loadingSeconds
        );

        contexts.put(simulationRunId, context);

        log.info("[재생] runId={} 시작 (로봇 {}대, 작업 {}건, 배속 {}x, 이동 {}초/칸)",
                simulationRunId, runtimes.size(), scheduled.size(), speed, moveSeconds);
    }

    /* =========================================================
       재생 진행
    ========================================================= */

    /**
     * 스케줄러가 주기적으로 호출한다.
     *
     * @param tickSeconds 실제 경과 시간(초)
     */
    @Transactional
    public void tick(double tickSeconds) {
        if (contexts.isEmpty()) {
            return;
        }

        for (Long runId : List.copyOf(contexts.keySet())) {
            PlaybackContext context = contexts.get(runId);
            if (context == null) {
                continue;
            }

            SimulationRun run = simulationRunRepository.findById(runId).orElse(null);

            if (run == null || isTerminated(run.getStatus())) {
                contexts.remove(runId);
                continue;
            }

            // 일시정지 중에는 시계도 멈춘다
            if (run.getStatus() != SimulationRunStatus.RUNNING) {
                continue;
            }

            advance(context, tickSeconds);

            if (context.isFinished()) {
                contexts.remove(runId);
                log.info("[재생] runId={} 모든 작업 수행 완료 (시뮬 시각 {}초)",
                        runId, Math.round(context.getClockSeconds()));
            }
        }
    }

    private void advance(PlaybackContext context, double tickSeconds) {
        context.advanceClock(tickSeconds);

        // 1) 발생 시각이 된 작업 투입
        for (Long taskId : context.releaseDueTasks()) {
            log.info("[재생] 시뮬 {}초 - 작업 {} 발생",
                    Math.round(context.getClockSeconds()), taskId);
        }

        // 2) 로봇별 진행
        for (RobotRuntime robot : context.getRobots()) {
            step(context, robot);
        }
    }

    /**
     * 로봇 한 대의 다음 동작을 수행한다.
     * 현재 동작이 아직 끝나지 않았으면 아무것도 하지 않는다.
     */
    private void step(PlaybackContext context, RobotRuntime robot) {
        if (context.getClockSeconds() < robot.getBusyUntilSeconds()) {
            return;
        }

        try {
            switch (robot.getPhase()) {
                case IDLE -> tryStartNextTask(context, robot);
                case MOVING_TO_START -> moveOrArrive(context, robot, true);
                case PICKING -> beginMoveToEnd(context, robot);
                case MOVING_TO_END -> moveOrArrive(context, robot, false);
                case DROPPING -> finishTask(context, robot);
            }
        } catch (Exception exception) {
            log.warn("[재생] 로봇 {} 처리 실패: {}", robot.getRobotId(), exception.getMessage());
            robot.setPhase(RobotRuntime.Phase.IDLE);
            robot.setCurrentTaskId(null);
        }
    }

    /** 유휴 로봇이 대기 중인 작업을 집어간다. */
    private void tryStartNextTask(PlaybackContext context, RobotRuntime robot) {
        if (!context.hasReadyTask()) {
            return;
        }

        Long taskId = context.pollReadyTask();
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }

        // DB에 배정 반영
        if (task.getStatus() == TaskStatus.PENDING) {
            task.assignRobot(robotRepository.getReferenceById(robot.getRobotId()));
            broadcastTask(task);
        }

        List<Long> path = pathFinder.findPath(
                context.getAdjacency(), robot.getCurrentNodeId(), task.getStartNode().getId());

        robot.setCurrentTaskId(taskId);
        robot.setPath(path);
        robot.setPhase(RobotRuntime.Phase.MOVING_TO_START);
        robot.setStatus(RobotStatus.ASSIGNED);

        publish(context, robot);

        log.info("[재생] 시뮬 {}초 - 로봇 {} 이 작업 {} 시작 (경로 {}칸)",
                Math.round(context.getClockSeconds()), robot.getRobotId(), taskId, path.size());
    }

    /** 경로를 한 칸 이동하거나, 도착했으면 다음 단계로 넘어간다. */
    private void moveOrArrive(PlaybackContext context, RobotRuntime robot, boolean towardStart) {
        if (robot.hasRemainingPath()) {
            Long nextNode = robot.pollNextNode();

            robot.moveTo(nextNode);
            robot.setStatus(RobotStatus.MOVING);
            robot.consumeBattery(BATTERY_PER_MOVE);
            robot.setBusyUntilSeconds(
                    context.getClockSeconds() + context.getMoveSecondsPerNode());

            publish(context, robot);
            return;
        }

        // 도착 - 더 이상 이동하지 않으므로 보간 정보를 지운다
        robot.stopMoving();

        if (towardStart) {
            robot.setPhase(RobotRuntime.Phase.PICKING);
            robot.setStatus(RobotStatus.PICKING);
            robot.consumeBattery(BATTERY_PER_WORK);
            robot.setBusyUntilSeconds(
                    context.getClockSeconds() + context.getPickingSeconds());

            startTask(robot.getCurrentTaskId());
            publish(context, robot);
        } else {
            Task task = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
            RobotStatus dropStatus = task != null && task.getTaskType() == TaskType.INBOUND
                    ? RobotStatus.PUTAWAY
                    : RobotStatus.RELOCATION;

            robot.setPhase(RobotRuntime.Phase.DROPPING);
            robot.setStatus(dropStatus);
            robot.consumeBattery(BATTERY_PER_WORK);
            robot.setBusyUntilSeconds(
                    context.getClockSeconds() + context.getLoadingSeconds());

            publish(context, robot);
        }
    }

    /** 집품이 끝나면 도착지로 향한다. */
    private void beginMoveToEnd(PlaybackContext context, RobotRuntime robot) {
        Task task = taskRepository.findById(robot.getCurrentTaskId()).orElse(null);
        if (task == null) {
            robot.setPhase(RobotRuntime.Phase.IDLE);
            robot.setCurrentTaskId(null);
            return;
        }

        List<Long> path = pathFinder.findPath(
                context.getAdjacency(), robot.getCurrentNodeId(), task.getEndNode().getId());

        robot.setPath(path);
        robot.setPhase(RobotRuntime.Phase.MOVING_TO_END);

        moveOrArrive(context, robot, false);
    }

    /** 적재가 끝나면 작업을 완료 처리하고 대기 상태로 돌아간다. */
    private void finishTask(PlaybackContext context, RobotRuntime robot) {
        Long taskId = robot.getCurrentTaskId();

        if (taskId != null) {
            try {
                taskService.completeTask(taskId);
                log.info("[재생] 시뮬 {}초 - 작업 {} 완료 (로봇 {})",
                        Math.round(context.getClockSeconds()), taskId, robot.getRobotId());
            } catch (Exception exception) {
                log.warn("[재생] 작업 {} 완료 처리 실패: {}", taskId, exception.getMessage());
            }
        }

        robot.setCurrentTaskId(null);
        robot.setPhase(RobotRuntime.Phase.IDLE);
        robot.setStatus(RobotStatus.IDLE);
        robot.stopMoving();

        publish(context, robot);
    }

    private void startTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        taskRepository.findById(taskId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                task.start();
                broadcastTask(task);
            }
        });
    }

    /* =========================================================
       상태 전송
    ========================================================= */

    /**
     * 로봇 상태를 Redis에 저장하고 WebSocket으로 브로드캐스트한다.
     *
     * 이동 중이면 다음 노드와 도착까지 남은 시간을 함께 보낸다.
     * 프론트는 이 값으로 두 노드 사이를 보간해 부드럽게 그린다.
     */
    private void publish(PlaybackContext context, RobotRuntime robot) {
        Long nextNodeId = null;
        String nextNodeCode = null;
        Double arrivalInSeconds = null;

        if (robot.getStatus() == RobotStatus.MOVING) {
            // 현재 이동이 끝나기까지 남은 시간
            double remaining = robot.getBusyUntilSeconds() - context.getClockSeconds();

            if (remaining > 0) {
                // 지금 향하고 있는 노드는 방금 진입한 currentNode 이므로,
                // 화면에서는 "직전 노드 -> 현재 노드" 구간을 보간한다.
                nextNodeId = robot.getCurrentNodeId();
                nextNodeCode = nodeCodeCache.get(nextNodeId);
                // 배속을 반영한 실제 경과 시간으로 환산
                arrivalInSeconds = remaining / context.getSpeed();
            }
        }

        RobotState state = new RobotState(
                robot.getRobotId(),
                context.getWarehouseId(),
                robot.getPreviousNodeId() == null
                        ? robot.getCurrentNodeId()
                        : robot.getPreviousNodeId(),
                nodeCodeCache.get(
                        robot.getPreviousNodeId() == null
                                ? robot.getCurrentNodeId()
                                : robot.getPreviousNodeId()),
                nextNodeId,
                nextNodeCode,
                arrivalInSeconds,
                robot.batteryPercent(),
                robot.getStatus(),
                robot.getCurrentTaskId(),
                LocalDateTime.now()
        );

        simulationRunStateStore.save(context.getSimulationRunId(), state);
        messagingTemplate.convertAndSend(
                robotTopic(context.getSimulationRunId()),
                RobotStateResponse.from(state)
        );
    }

    /* =========================================================
       정리 / 유틸
    ========================================================= */

    public void clear(Long simulationRunId) {
        contexts.remove(simulationRunId);
    }

    /**
     * 재생 중인 시뮬레이션의 배속을 즉시 변경한다.
     *
     * 배속이 바뀌면 화면 보간에 쓰이는 "도착까지 남은 시간"도 달라지므로,
     * 이동 중인 로봇의 상태를 다시 내보내 화면이 바로 반응하게 한다.
     *
     * @return 재생 중이어서 실제로 반영했으면 true
     */
    public boolean changeSpeed(Long simulationRunId, double newSpeed) {
        PlaybackContext context = contexts.get(simulationRunId);

        if (context == null) {
            return false;
        }

        context.changeSpeed(newSpeed);

        for (RobotRuntime robot : context.getRobots()) {
            publish(context, robot);
        }

        log.info("[재생] runId={} 배속 변경 -> {}x", simulationRunId, newSpeed);
        return true;
    }

    public boolean isPlaying(Long simulationRunId) {
        return contexts.containsKey(simulationRunId);
    }

    /** 현재 시뮬레이션 시각(초). 진행 중이 아니면 0. */
    public double currentClock(Long simulationRunId) {
        PlaybackContext context = contexts.get(simulationRunId);
        return context == null ? 0 : context.getClockSeconds();
    }

    private boolean isTerminated(SimulationRunStatus status) {
        return status == SimulationRunStatus.COMPLETED
                || status == SimulationRunStatus.FAILED
                || status == SimulationRunStatus.STOPPED
                || status == SimulationRunStatus.CREATED;
    }

    private void cacheNodeCodes(Long warehouseId) {
        for (WarehouseNode node : warehouseNodeRepository.findAllByWarehouse_Id(warehouseId)) {
            if (node.getNodeCode() != null) {
                nodeCodeCache.put(node.getId(), node.getNodeCode());
            }
        }
    }

    private void broadcastTask(Task task) {
        messagingTemplate.convertAndSend(TASK_TOPIC, new TaskResponse(task));
    }

    private String robotTopic(Long simulationRunId) {
        return RUN_TOPIC + "/" + simulationRunId + "/robots";
    }
}
