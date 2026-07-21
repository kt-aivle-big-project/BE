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
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RobotStateServiceTest {

    private SimpMessagingTemplate messagingTemplate;
    private RobotRepository robotRepository;
    private WarehouseNodeRepository nodeRepository;
    private RobotStateStore stateStore;
    private RobotStateService service;

    @BeforeEach
    void setUp() {
        robotRepository = mock(RobotRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        nodeRepository = mock(WarehouseNodeRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        stateStore = mock(RobotStateStore.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        service = new RobotStateService(
                robotRepository,
                warehouseRepository,
                nodeRepository,
                taskRepository,
                stateStore,
                new RobotStateTransitionValidator(),
                messagingTemplate
        );
    }

    @Test
    void updatesValidRobotState() {
        Warehouse warehouse = warehouse(1L);
        Robot robot = robot(3L, warehouse);
        WarehouseNode node = node(125L, warehouse);
        LocalDateTime eventTime = LocalDateTime.of(2026, 7, 20, 15, 30);
        RobotStateUpdateRequest request = new RobotStateUpdateRequest(
                125L, 67, RobotStatus.IDLE, null, eventTime
        );
        when(robotRepository.findById(3L)).thenReturn(Optional.of(robot));
        when(nodeRepository.findById(125L)).thenReturn(Optional.of(node));
        when(stateStore.findByRobotId(3L)).thenReturn(Optional.empty());
        when(stateStore.save(any(RobotState.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RobotStateResponse response = service.updateState(3L, request);

        assertThat(response.robotId()).isEqualTo(3L);
        assertThat(response.warehouseId()).isEqualTo(1L);
        assertThat(response.currentNodeId()).isEqualTo(125L);
        assertThat(response.batteryLevel()).isEqualTo(67);
        assertThat(response.updatedAt()).isEqualTo(eventTime);
    }

    @Test
    void rejectsNodeFromDifferentWarehouse() {
        Robot robot = robot(3L, warehouse(1L));
        WarehouseNode node = node(125L, warehouse(2L));
        when(robotRepository.findById(3L)).thenReturn(Optional.of(robot));
        when(nodeRepository.findById(125L)).thenReturn(Optional.of(node));
        RobotStateUpdateRequest request = new RobotStateUpdateRequest(
                125L, 67, RobotStatus.IDLE, null, LocalDateTime.now()
        );

        assertThatThrownBy(() -> service.updateState(3L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_ROBOT_LOCATION));
    }

    @Test
    void rejectsOlderStateEvent() {
        Warehouse warehouse = warehouse(1L);
        Robot robot = robot(3L, warehouse);
        WarehouseNode node = node(125L, warehouse);
        LocalDateTime currentTime = LocalDateTime.of(2026, 7, 20, 15, 30, 10);
        when(robotRepository.findById(3L)).thenReturn(Optional.of(robot));
        when(nodeRepository.findById(125L)).thenReturn(Optional.of(node));
        when(stateStore.findByRobotId(3L)).thenReturn(Optional.of(new RobotState(
                3L, 1L, 125L, 70, RobotStatus.IDLE, null, currentTime
        )));
        RobotStateUpdateRequest request = new RobotStateUpdateRequest(
                125L, 69, RobotStatus.IDLE, null, currentTime.minusSeconds(1)
        );

        assertThatThrownBy(() -> service.updateState(3L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.STALE_ROBOT_STATE));
    }

    private Warehouse warehouse(Long id) {
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouse.getId()).thenReturn(id);
        return warehouse;
    }

    private Robot robot(Long id, Warehouse warehouse) {
        Robot robot = mock(Robot.class);
        when(robot.getId()).thenReturn(id);
        when(robot.getWarehouse()).thenReturn(warehouse);
        return robot;
    }

    private WarehouseNode node(Long id, Warehouse warehouse) {
        WarehouseNode node = mock(WarehouseNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getWarehouse()).thenReturn(warehouse);
        return node;
    }
}
