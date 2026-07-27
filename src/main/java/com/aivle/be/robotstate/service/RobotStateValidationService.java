package com.aivle.be.robotstate.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.robotstate.controller.request.RobotStateUpdateRequest;
import com.aivle.be.robotstate.validation.RobotStateTransitionValidator;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RobotStateValidationService {

    private final RobotRepository robotRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final TaskRepository taskRepository;
    private final RobotStateTransitionValidator transitionValidator;

    @Transactional(readOnly = true)
    public RobotState validate(
            Long robotId,
            RobotStateUpdateRequest request,
            Optional<RobotState> currentState
    ) {
        Robot robot = robotRepository.findById(robotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROBOT_NOT_FOUND));
        WarehouseNode node = warehouseNodeRepository.findById(request.currentNodeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));

        validateSameWarehouse(robot, node);
        validateTask(robot, request.currentTaskId(), request.status());
        validateEventOrder(currentState, request);

        // 외부에서 보고된 상태에는 이동 예정 정보가 없다
        return RobotState.stationary(
                robotId,
                robot.getWarehouse().getId(),
                node.getId(),
                node.getNodeCode(),
                request.batteryLevel(),
                request.status(),
                request.currentTaskId(),
                request.eventTime()
        );
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

    private void validateEventOrder(
            Optional<RobotState> currentState,
            RobotStateUpdateRequest request
    ) {
        currentState.ifPresent(state -> {
            if (request.eventTime().isBefore(state.updatedAt())) {
                throw new BusinessException(ErrorCode.STALE_ROBOT_STATE);
            }
            transitionValidator.validate(state.status(), request.status());
        });
    }
}
