package com.aivle.be.robot.service;

import com.aivle.be.event.repository.EventRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.dto.RobotCreateRequest;
import com.aivle.be.robot.dto.RobotResponse;
import com.aivle.be.robot.dto.RobotUpdateRequest;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RobotService {

    private static final Set<SimulationRunStatus> ACTIVE_RUN_STATUSES = Set.of(
            SimulationRunStatus.RUNNING,
            SimulationRunStatus.PAUSED,
            SimulationRunStatus.QUIESCING,
            SimulationRunStatus.REPLANNING,
            SimulationRunStatus.PENDING_ACTIVATION
    );
    private static final Set<TaskStatus> ACTIVE_TASK_STATUSES = Set.of(
            TaskStatus.ASSIGNED,
            TaskStatus.IN_PROGRESS
    );

    private final RobotRepository robotRepository;
    private final RobotSpecRepository robotSpecRepository;
    private final WarehouseRepository warehouseRepository;
    private final SimulationRunRobotRepository simulationRunRobotRepository;
    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;

    @Transactional
    public RobotResponse createRobot(RobotCreateRequest request) {
        RobotSpec robotSpec = robotSpecRepository
                .findById(request.getRobotSpecId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_SPEC_NOT_FOUND));

        Warehouse warehouse = warehouseRepository
                .findById(request.getWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        Robot robot = Robot.create(
                robotSpec,
                warehouse,
                request.getNodeId(),
                request.getBattery(),
                request.getStatus()
        );

        return RobotResponse.from(robotRepository.save(robot));
    }

    public RobotResponse getRobot(Long robotId) {
        Robot robot = findRobot(robotId);
        return RobotResponse.from(robot);
    }

    public List<RobotResponse> getRobots() {
        return robotRepository.findAll()
                .stream()
                .map(RobotResponse::from)
                .toList();
    }

    public List<RobotResponse> getRobotsByWarehouse(Long warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
        }

        return robotRepository.findAllByWarehouse_Id(warehouseId)
                .stream()
                .map(RobotResponse::from)
                .toList();
    }

    @Transactional
    public RobotResponse updateRobot(
            Long robotId,
            RobotUpdateRequest request
    ) {
        Robot robot = findRobot(robotId);
        requireNotActive(robotId);

        RobotSpec robotSpec = robotSpecRepository
                .findById(request.getRobotSpecId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_SPEC_NOT_FOUND));

        Warehouse warehouse = warehouseRepository
                .findById(request.getWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        robot.update(
                robotSpec,
                warehouse,
                request.getNodeId(),
                request.getBattery(),
                request.getStatus()
        );

        return RobotResponse.from(robot);
    }

    @Transactional
    public void deleteRobot(Long robotId) {
        Robot robot = findRobot(robotId);
        requireNotActive(robotId);

        eventRepository.clearRobotReference(robotId);
        taskRepository.clearRobotReference(robotId);
        simulationRunRobotRepository.deleteAllByRobotId(robotId);
        robotRepository.deleteByIdDirectly(robot.getId());
    }

    private Robot findRobot(Long robotId) {
        return robotRepository.findById(robotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
    }

    private void requireNotActive(Long robotId) {
        boolean activeRun = simulationRunRobotRepository
                .existsByRobot_IdAndSimulationRun_StatusIn(robotId, ACTIVE_RUN_STATUSES);
        boolean activeTask = taskRepository.existsByRobot_IdAndStatusIn(
                robotId,
                ACTIVE_TASK_STATUSES
        );
        if (activeRun || activeTask) {
            throw new BusinessException(ErrorCode.ROBOT_IN_ACTIVE_USE);
        }
    }
}
