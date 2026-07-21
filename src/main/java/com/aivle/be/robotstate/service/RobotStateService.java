package com.aivle.be.robotstate.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.robotstate.dto.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.dto.response.RobotStateResponse;
import com.aivle.be.robotstate.repository.RobotStateStore;
import com.aivle.be.robotstate.validation.RobotStateTransitionValidator;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RobotStateService {

    private static final String TOPIC = "/topic/robots";

    private final RobotRepository robotRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskRepository taskRepository;
    private final RobotStateStore robotStateStore;
    private final RobotStateTransitionValidator transitionValidator;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public RobotStateResponse updateState(Long robotId, RobotStateUpdateRequest request) {
        Robot robot = robotRepository.findById(robotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
        WarehouseNode node = warehouseNodeRepository.findById(request.currentNodeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        validateSameWarehouse(robot, node);
        validateTask(robot, request.currentTaskId(), request.status());
        validateEventOrder(robotId, request);

        RobotState state = new RobotState(
                robotId,
                robot.getWarehouse().getId(),
                node.getId(),
                request.batteryLevel(),
                request.status(),
                request.currentTaskId(),
                request.eventTime()
        );

        RobotStateResponse response = RobotStateResponse.from(robotStateStore.save(state));
        messagingTemplate.convertAndSend(TOPIC, response);
        return response;
    }

    public RobotStateResponse getState(Long robotId) {
        return robotStateStore.findByRobotId(robotId)
                .map(RobotStateResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_STATE_NOT_FOUND));
    }

    public List<RobotStateResponse> getWarehouseStates(Long warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
        }

        return robotStateStore.findAllByWarehouseId(warehouseId).stream()
                .map(RobotStateResponse::from)
                .toList();
    }

    private void validateSameWarehouse(Robot robot, WarehouseNode node) {
        if (!robot.getWarehouse().getId().equals(node.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.INVALID_ROBOT_LOCATION);
        }
    }

    private void validateTask(Robot robot, Long taskId, RobotStatus status) {
        if ((status == RobotStatus.ASSIGNED || status == RobotStatus.WORKING) && taskId == null) {
            throw new BusinessException(ErrorCode.INVALID_ROBOT_TASK);
        }
        if (taskId == null) {
            return;
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        if (task.getRobot() == null
                || !task.getRobot().getId().equals(robot.getId())
                || !task.getWarehouse().getId().equals(robot.getWarehouse().getId())) {
            throw new BusinessException(ErrorCode.INVALID_ROBOT_TASK);
        }
    }

    private void validateEventOrder(Long robotId, RobotStateUpdateRequest request) {
        robotStateStore.findByRobotId(robotId).ifPresent(currentState -> {
            if (request.eventTime().isBefore(currentState.updatedAt())) {
                throw new BusinessException(ErrorCode.STALE_ROBOT_STATE);
            }
            transitionValidator.validate(currentState.status(), request.status());
        });
    }
}