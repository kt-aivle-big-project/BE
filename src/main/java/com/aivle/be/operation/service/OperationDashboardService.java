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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OperationDashboardService {

    private static final List<String> HOUR_SLOTS = List.of(
            "00시", "02시", "04시", "06시", "08시", "10시",
            "12시", "14시", "16시", "18시", "20시", "22시"
    );

    private static final List<String> STATUS_KEYS = List.of(
            "AVAILABLE", "WORKING", "CHARGING", "UNAVAILABLE", "OFFLINE", "ERROR"
    );

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

    public OperationDashboardResponse getDashboard(
            Long warehouseId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate from = startDate == null ? LocalDate.now() : startDate;
        LocalDate to = endDate == null ? from : endDate;

        LocalDateTime fromTime = from.atStartOfDay();
        LocalDateTime toTime = to.plusDays(1).atStartOfDay();

        List<Task> tasks = findTasks(warehouseId, fromTime, toTime);
        List<Event> events = findEvents(warehouseId, fromTime, toTime);
        List<Robot> robots = findRobots(warehouseId);

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

    private int batteryOf(Robot robot, Map<Long, RobotState> runtimeStates) {
        RobotState state = runtimeStates.get(robot.getId());

        if (state != null && state.batteryLevel() != null) {
            return state.batteryLevel();
        }

        return robot.getBattery() == null ? 100 : robot.getBattery();
    }

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
