package com.aivle.be.scenario.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.scenario.controller.request.ScenarioRequest;
import com.aivle.be.scenario.controller.response.ScenarioResponse;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioServiceTest {

    private static final long WAREHOUSE_ID = 11L;
    private static final long USER_ID = 7L;

    @Mock private ScenarioRepository scenarioRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private RobotRepository robotRepository;
    @InjectMocks private ScenarioService scenarioService;

    @Test
    void usesActualWarehouseRobotCountWhenRequestOmitsRobotCount() {
        Warehouse warehouse = ownedWarehouse();
        when(robotRepository.findAllByWarehouse_Id(WAREHOUSE_ID))
                .thenReturn(List.of(
                        mock(Robot.class),
                        mock(Robot.class),
                        mock(Robot.class)
                ));
        when(scenarioRepository.existsByWarehouse_IdAndScenarioCode(
                WAREHOUSE_ID, "S1"
        )).thenReturn(false);
        when(scenarioRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScenarioResponse response = scenarioService.create(
                request(null),
                AuthenticatedRequester.user(USER_ID)
        );

        assertThat(response.warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(response.robotCount()).isEqualTo(3);
    }

    @Test
    void capsRequestedRobotCountAtActualWarehouseRobotCount() {
        ownedWarehouse();
        when(robotRepository.findAllByWarehouse_Id(WAREHOUSE_ID))
                .thenReturn(List.of(mock(Robot.class), mock(Robot.class)));
        when(scenarioRepository.existsByWarehouse_IdAndScenarioCode(
                WAREHOUSE_ID, "S1"
        )).thenReturn(false);
        when(scenarioRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScenarioResponse response = scenarioService.create(
                request(10),
                AuthenticatedRequester.user(USER_ID)
        );

        assertThat(response.robotCount()).isEqualTo(2);
    }

    @Test
    void rejectsScenarioCreationForSharedWarehouse() {
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouseRepository.findById(WAREHOUSE_ID))
                .thenReturn(Optional.of(warehouse));
        when(warehouse.isShared()).thenReturn(true);

        assertError(
                () -> scenarioService.create(
                        request(null),
                        AuthenticatedRequester.user(USER_ID)
                ),
                ErrorCode.SHARED_WAREHOUSE_READ_ONLY
        );
        verifyNoInteractions(robotRepository);
    }

    @Test
    void rejectsScenarioCreationWhenWarehouseHasNoRobots() {
        ownedWarehouse();
        when(robotRepository.findAllByWarehouse_Id(WAREHOUSE_ID))
                .thenReturn(List.of());

        assertError(
                () -> scenarioService.create(
                        request(null),
                        AuthenticatedRequester.user(USER_ID)
                ),
                ErrorCode.NO_AVAILABLE_ROBOTS
        );
        verifyNoInteractions(scenarioRepository);
    }

    @Test
    void rejectsScenarioCreationForAnotherUsersWarehouse() {
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouseRepository.findById(WAREHOUSE_ID))
                .thenReturn(Optional.of(warehouse));
        when(warehouse.isOwnedBy(USER_ID)).thenReturn(false);

        assertError(
                () -> scenarioService.create(
                        request(null),
                        AuthenticatedRequester.user(USER_ID)
                ),
                ErrorCode.ACCESS_DENIED
        );
        verifyNoInteractions(robotRepository);
    }

    private Warehouse ownedWarehouse() {
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouseRepository.findById(WAREHOUSE_ID))
                .thenReturn(Optional.of(warehouse));
        when(warehouse.getId()).thenReturn(WAREHOUSE_ID);
        when(warehouse.isOwnedBy(USER_ID)).thenReturn(true);
        return warehouse;
    }

    private ScenarioRequest request(Integer robotCount) {
        return new ScenarioRequest(
                WAREHOUSE_ID,
                "테스트 시나리오",
                null,
                "설명",
                100,
                20,
                robotCount,
                null,
                null,
                null
        );
    }

    private void assertError(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }
}
