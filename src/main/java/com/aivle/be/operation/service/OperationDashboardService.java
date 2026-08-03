package com.aivle.be.operation.service;

import com.aivle.be.event.entity.Event;
import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.operation.controller.response.OperationDashboardResponse;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 운영 관리 화면에 필요한 값을 한 번에 모아 준다.
 *
 * <p>작업·이벤트·로봇을 각각 내려주고 화면에서 더하게 하면
 * 같은 계산이 화면마다 흩어지고, 작업이 쌓일수록 전부 받아야 해서 느려진다.
 * 그래서 집계는 여기서 하고 화면은 그리기만 한다.
 *
 * <p>로봇 상태는 두 곳에 있다.
 * <pre>
 *   robot 테이블   등록된 로봇의 기본값 (사용 가능 / 사용 불가)
 *   Redis         실행 중인 시뮬레이션의 실시간 상태 (이동 중, 충전 중, 오류 ...)
 * </pre>
 * 실행 중이면 실시간 상태가 더 정확하므로 그쪽을 우선한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationDashboardService {

    /** 화면 막대그래프 칸. 2시간 단위 12칸이며 라벨 문자열까지 화면과 같아야 한다. */
    private static final List<String> HOUR_SLOTS = List.of(
            "00시", "02시", "04시", "06시", "08시", "10시",
            "12시", "14시", "16시", "18시", "20시", "22시"
    );

    /** 화면 도넛 그래프의 상태 구분. 순서와 이름이 화면과 같아야 한다. */
    private static final List<String> STATUS_KEYS = List.of(
            "AVAILABLE", "WORKING", "CHARGING", "UNAVAILABLE", "OFFLINE", "ERROR"
    );

    /** 이 값 아래면 "충전 필요"로 센다. 시나리오 기본 충전 임계값과 같다. */
    private static final int LOW_BATTERY_THRESHOLD = 20;

    /** 최근 작업 표에 보여줄 건수 */
    private static final int RECENT_TASK_LIMIT = 10;

    private static final Set<SimulationRunStatus> LIVE_RUN_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED
    );

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final RobotRepository robotRepository;
    private final WarehouseRepository warehouseRepository;
    private final SimulationRunRepository simulationRunRepository;
    private final SimulationRunStateStore simulationRunStateStore;

    /**
     * @param warehouseId 창고 하나만 볼 때. null 이면 전체 창고
     * @param startDate   조회 시작일 (포함)
     * @param endDate     조회 종료일 (포함)
     */
    public OperationDashboardResponse getDashboard(
            Long warehouseId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate from = startDate == null ? LocalDate.now() : startDate;
        LocalDate to = endDate == null ? from : endDate;

        // 종료일도 포함해야 하므로 다음 날 0시 직전까지 본다
        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        List<Task> tasks = findTasks(warehouseId, fromTime, toTime);
        List<Event> events = findEvents(warehouseId, fromTime, toTime);
        List<Robot> robots = findRobots(warehouseId);

        // 창고별 처리량은 창고끼리 비교하는 그래프라 창고 필터를 걸지 않는다.
        // 필터를 걸면 고르지 않은 창고가 0 으로 나와 "작업이 없다"처럼 보인다.
        List<Task> tasksForComparison = warehouseId == null
                ? tasks
                : findTasks(null, fromTime, toTime);

        Map<Long, String> warehouseNames = loadWarehouseNames();
        Map<Long, RobotState> runtimeStates = loadRuntimeStates(warehouseId);
        Map<String, Long> statusCounts = countRobotStatus(robots, runtimeStates);

        return new OperationDashboardResponse(
                buildSummary(tasks, robots, runtimeStates, statusCounts),
                buildHourlyTaskVolume(tasks),
                bucketByHour(events.stream().map(Event::getOccurredAt).toList()),
                toStatusCounts(statusCounts),
                buildWarehouseThroughput(tasksForComparison, warehouseNames),
                buildRecentTasks(tasks, warehouseNames),
                LocalDateTime.now().format(TIMESTAMP)
        );
    }

    /**
     * 같은 조건의 작업을 자르지 않고 전부 돌려준다.
     *
     * <p>대시보드는 화면이 무거워지지 않게 최근 10건만 담는데,
     * 「전체 보기」 팝업은 기간 안의 모든 작업을 보여줘야 해서 따로 둔다.
     *
     * @param warehouseId 창고 하나만 볼 때. null 이면 전체 창고
     * @param startDate   조회 시작일 (포함)
     * @param endDate     조회 종료일 (포함)
     */
    public List<OperationDashboardResponse.RecentTask> getTasks(
            Long warehouseId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate from = startDate == null ? LocalDate.now() : startDate;
        LocalDate to = endDate == null ? from : endDate;

        List<Task> tasks = findTasks(
                warehouseId,
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay()
        );

        return toRecentTasks(tasks, loadWarehouseNames());
    }

    /* =========================================================
       조회
    ========================================================= */

    private List<Task> findTasks(Long warehouseId, LocalDateTime from, LocalDateTime to) {
        return warehouseId == null
                ? taskRepository
                        .findAllByRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAtDesc(
                                from, to)
                : taskRepository
                        .findAllByWarehouse_IdAndRequestedAtGreaterThanEqualAndRequestedAtLessThanOrderByRequestedAtDesc(
                                warehouseId, from, to);
    }

    private List<Event> findEvents(Long warehouseId, LocalDateTime from, LocalDateTime to) {
        return warehouseId == null
                ? eventRepository.findAllByOccurredAtGreaterThanEqualAndOccurredAtLessThan(from, to)
                : eventRepository.findAllByWarehouse_IdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        warehouseId, from, to);
    }

    private List<Robot> findRobots(Long warehouseId) {
        return warehouseId == null
                ? robotRepository.findAll()
                : robotRepository.findAllByWarehouse_Id(warehouseId);
    }

    private Map<Long, String> loadWarehouseNames() {
        Map<Long, String> names = new HashMap<>();

        for (Warehouse warehouse : warehouseRepository.findAll()) {
            names.put(warehouse.getId(), warehouse.getName());
        }

        return names;
    }

    /**
     * 실행 중인 시뮬레이션의 로봇 실시간 상태를 모은다.
     *
     * <p>실행 중인 게 없으면 빈 결과다. 그 경우 로봇 상태는 DB 값만 쓴다.
     */
    private Map<Long, RobotState> loadRuntimeStates(Long warehouseId) {
        List<SimulationRun> runs = warehouseId == null
                ? simulationRunRepository.findAllByStatusIn(LIVE_RUN_STATUSES)
                : simulationRunRepository.findAllByWarehouse_IdAndStatusIn(
                        warehouseId, LIVE_RUN_STATUSES);

        Map<Long, RobotState> states = new HashMap<>();

        for (SimulationRun run : runs) {
            for (RobotState state : simulationRunStateStore.findAll(run.getId())) {
                states.put(state.robotId(), state);
            }
        }

        return states;
    }

    /* =========================================================
       집계
    ========================================================= */

    private OperationDashboardResponse.Summary buildSummary(
            List<Task> tasks,
            List<Robot> robots,
            Map<Long, RobotState> runtimeStates,
            Map<String, Long> statusCounts
    ) {
        long total = tasks.size();
        long done = tasks.stream()
                .filter(task -> task.getStatus() == TaskStatus.DONE)
                .count();

        int completionRate = total == 0
                ? 0
                : (int) Math.round(done * 100.0 / total);

        long lowBattery = robots.stream()
                .filter(robot -> batteryOf(robot, runtimeStates) < LOW_BATTERY_THRESHOLD)
                .count();

        return new OperationDashboardResponse.Summary(
                total,
                completionRate,
                statusCounts.getOrDefault("WORKING", 0L),
                lowBattery,
                statusCounts.getOrDefault("ERROR", 0L)
        );
    }

    /** 실시간 배터리가 있으면 그 값을, 없으면 DB 값을 쓴다. */
    private int batteryOf(Robot robot, Map<Long, RobotState> runtimeStates) {
        RobotState state = runtimeStates.get(robot.getId());

        if (state != null && state.batteryLevel() != null) {
            return state.batteryLevel();
        }

        return robot.getBattery() == null ? 100 : robot.getBattery();
    }

    /**
     * 로봇을 화면의 6가지 상태로 분류한다.
     *
     * <p>실행 중이면 Redis 의 실시간 상태를, 아니면 DB 의 사용 가능 여부를 본다.
     * 실시간 상태의 세부 작업 유형(PICKING, PUTAWAY 등)은 모두 WORKING 으로 묶는다.
     */
    private Map<String, Long> countRobotStatus(
            List<Robot> robots,
            Map<Long, RobotState> runtimeStates
    ) {
        Map<String, Long> counts = new LinkedHashMap<>();
        STATUS_KEYS.forEach(key -> counts.put(key, 0L));

        for (Robot robot : robots) {
            String key = classify(robot, runtimeStates.get(robot.getId()));
            counts.merge(key, 1L, Long::sum);
        }

        return counts;
    }

    private String classify(Robot robot, RobotState state) {
        if (state == null || state.status() == null) {
            // 실행 중이 아니면 DB 값만 안다
            return robot.getStatus() == RobotAvailabilityStatus.UNAVAILABLE
                    ? "UNAVAILABLE"
                    : "AVAILABLE";
        }

        RobotStatus status = state.status();

        if (status == RobotStatus.IDLE) {
            return "AVAILABLE";
        }
        if (status == RobotStatus.CHARGING) {
            return "CHARGING";
        }
        if (status == RobotStatus.ERROR) {
            return "ERROR";
        }
        if (status == RobotStatus.OFFLINE) {
            return "OFFLINE";
        }

        // ASSIGNED / MOVING / WORKING / PICKING / PUTAWAY / REPLENISH / RELOCATION
        return "WORKING";
    }

    private List<OperationDashboardResponse.StatusCount> toStatusCounts(Map<String, Long> counts) {
        return STATUS_KEYS.stream()
                .map(key -> new OperationDashboardResponse.StatusCount(
                        key, counts.getOrDefault(key, 0L)))
                .toList();
    }

    /**
     * 시간대별 작업량.
     *
     * <p>발생 건수와 그중 완료된 건수를 함께 담는다.
     * 화면에서 "작업 수 / 완료 작업" 을 골라 그릴 수 있게 하기 위해서다.
     * 두 값 모두 요청 시각(requestedAt) 기준으로 같은 칸에 넣는다.
     */
    private List<OperationDashboardResponse.HourlyCount> buildHourlyTaskVolume(List<Task> tasks) {
        long[] totals = new long[HOUR_SLOTS.size()];
        long[] completed = new long[HOUR_SLOTS.size()];

        for (Task task : tasks) {
            if (task.getRequestedAt() == null) {
                continue;
            }

            int slot = task.getRequestedAt().getHour() / 2;
            totals[slot]++;

            if (task.getStatus() == TaskStatus.DONE) {
                completed[slot]++;
            }
        }

        List<OperationDashboardResponse.HourlyCount> result = new ArrayList<>();

        for (int index = 0; index < HOUR_SLOTS.size(); index++) {
            result.add(new OperationDashboardResponse.HourlyCount(
                    HOUR_SLOTS.get(index), totals[index], completed[index]));
        }

        return result;
    }

    /** 시각 목록을 2시간 단위 12칸으로 센다. 이벤트처럼 완료 개념이 없는 값에 쓴다. */
    private List<OperationDashboardResponse.HourlyCount> bucketByHour(
            List<LocalDateTime> timestamps
    ) {
        long[] buckets = new long[HOUR_SLOTS.size()];

        for (LocalDateTime timestamp : timestamps) {
            if (timestamp == null) {
                continue;
            }
            buckets[timestamp.getHour() / 2]++;
        }

        List<OperationDashboardResponse.HourlyCount> result = new ArrayList<>();

        for (int index = 0; index < HOUR_SLOTS.size(); index++) {
            result.add(new OperationDashboardResponse.HourlyCount(
                    HOUR_SLOTS.get(index), buckets[index], 0L));
        }

        return result;
    }

    /**
     * 창고별 완료 작업 수.
     *
     * <p>창고를 하나 골라도 전체 창고를 함께 보여 준다. 비교가 목적이기 때문이다.
     * 작업이 없는 창고는 0 으로 나온다.
     */
    private List<OperationDashboardResponse.WarehouseCount> buildWarehouseThroughput(
            List<Task> tasks,
            Map<Long, String> warehouseNames
    ) {
        Map<Long, Long> doneCounts = new LinkedHashMap<>();
        Map<Long, Long> totalCounts = new LinkedHashMap<>();

        for (Task task : tasks) {
            Long id = task.getWarehouse().getId();

            totalCounts.merge(id, 1L, Long::sum);

            if (task.getStatus() == TaskStatus.DONE) {
                doneCounts.merge(id, 1L, Long::sum);
            }
        }

        return warehouseNames.entrySet().stream()
                .map(entry -> {
                    long done = doneCounts.getOrDefault(entry.getKey(), 0L);
                    long total = totalCounts.getOrDefault(entry.getKey(), 0L);

                    return new OperationDashboardResponse.WarehouseCount(
                            entry.getKey(),
                            entry.getValue(),
                            done,
                            total,
                            total == 0 ? 0 : (int) Math.round(done * 100.0 / total)
                    );
                })
                .sorted((left, right) -> Long.compare(left.warehouseId(), right.warehouseId()))
                .toList();
    }

    private List<OperationDashboardResponse.RecentTask> buildRecentTasks(
            List<Task> tasks,
            Map<Long, String> warehouseNames
    ) {
        return toRecentTasks(
                tasks.stream().limit(RECENT_TASK_LIMIT).toList(),
                warehouseNames
        );
    }

    private List<OperationDashboardResponse.RecentTask> toRecentTasks(
            List<Task> tasks,
            Map<Long, String> warehouseNames
    ) {
        return tasks.stream()
                .map(task -> new OperationDashboardResponse.RecentTask(
                        task.getId(),
                        "T-" + task.getId(),
                        warehouseNames.get(task.getWarehouse().getId()),
                        task.getTaskType() == null ? null : task.getTaskType().name(),
                        task.getStatus() == null ? null : task.getStatus().name(),
                        format(task.getStartedAt()),
                        format(task.getCompletedAt()),
                        task.delayMinutes()
                ))
                .toList();
    }

    private String format(LocalDateTime timestamp) {
        return timestamp == null ? null : timestamp.format(TIMESTAMP);
    }
}
