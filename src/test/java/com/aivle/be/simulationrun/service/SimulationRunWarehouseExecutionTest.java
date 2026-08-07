package com.aivle.be.simulationrun.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.service.RobotStateValidationService;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleService;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationRunWarehouseExecutionTest {

    private static final String GUEST_A =
            "a4d70ea4-9a96-4c75-8414-24a43114a962";
    private static final String GUEST_B =
            "b5e81fb5-ab07-4d86-9525-35b54225ba73";

    @Mock private SimulationRunRepository simulationRunRepository;
    @Mock private SimulationRunRobotRepository simulationRunRobotRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private RobotRepository robotRepository;
    @Mock private SimulationRunStateStore simulationRunStateStore;
    @Mock private RobotStateValidationService robotStateValidationService;
    @Mock private WarehouseNodeRepository warehouseNodeRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private SimulationPlaybackService simulationPlaybackService;
    @Mock private SimulationCommandCycleService simulationCommandCycleService;
    @Mock private LaroInventoryReservationService inventoryReservationService;
    @Mock private UserRepository userRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Spy private GuestAccessPolicy guestAccessPolicy = new GuestAccessPolicy();

    @InjectMocks private SimulationRunService simulationRunService;

    @Test
    void userCreatesRunWithOwnPersonalSampleWarehouse() {
        User user = user(7L, "user-a@test.com");
        Warehouse template = warehouse(1L, user(1L, "template@test.com"));
        template.markShared();
        Warehouse personalCopy = Warehouse.createPersonalCopy(template, user);
        ReflectionTestUtils.setField(personalCopy, "id", 20L);
        prepareCreate(personalCopy, user);

        SimulationRunResponse response = simulationRunService.create(
                request(20L),
                AuthenticatedRequester.user(7L)
        );

        assertThat(response.warehouseId()).isEqualTo(20L);
        SimulationRun saved = captureSavedRun();
        assertThat(saved.getWarehouse()).isSameAs(personalCopy);
        assertThat(saved.getUser()).isSameAs(user);
    }

    @Test
    void userCannotCreateRunWithSharedTemplateWarehouse() {
        User user = user(7L, "user-a@test.com");
        Warehouse template = warehouse(1L, user);
        template.markShared();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(template));

        assertError(
                () -> simulationRunService.create(
                        request(1L),
                        AuthenticatedRequester.user(7L)
                ),
                ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
        );

        verify(simulationRunRepository, never()).save(any());
    }

    @Test
    void userCannotCreateRunWithAnotherUsersWarehouse() {
        Warehouse otherUsersWarehouse = warehouse(
                30L,
                user(8L, "user-b@test.com")
        );
        when(warehouseRepository.findById(30L))
                .thenReturn(Optional.of(otherUsersWarehouse));

        assertError(
                () -> simulationRunService.create(
                        request(30L),
                        AuthenticatedRequester.user(7L)
                ),
                ErrorCode.ACCESS_DENIED
        );

        verify(simulationRunRepository, never()).save(any());
    }

    @Test
    void existingCustomWarehouseCreationStillSucceeds() {
        User user = user(7L, "user-a@test.com");
        Warehouse custom = warehouse(40L, user);
        prepareCreate(custom, user);

        SimulationRunResponse response = simulationRunService.create(
                request(40L),
                AuthenticatedRequester.user(7L)
        );

        assertThat(response.warehouseId()).isEqualTo(40L);
    }

    @Test
    void userStartsRunWithOwnPersonalSampleWarehouse() {
        User user = user(7L, "user-a@test.com");
        Warehouse template = warehouse(1L, user(1L, "template@test.com"));
        template.markShared();
        Warehouse personalCopy = Warehouse.createPersonalCopy(template, user);
        ReflectionTestUtils.setField(personalCopy, "id", 20L);
        SimulationRun run = SimulationRun.create(
                personalCopy,
                LocalDateTime.now()
        );
        run.assignUser(user);
        ReflectionTestUtils.setField(run, "id", 100L);
        when(simulationRunRepository.findById(100L)).thenReturn(Optional.of(run));

        Robot robot = org.mockito.Mockito.mock(Robot.class);
        when(robot.getId()).thenReturn(5L);
        when(robot.getBattery()).thenReturn(100);
        when(robotRepository
                .findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                        20L,
                        RobotAvailabilityStatus.AVAILABLE
                ))
                .thenReturn(List.of(robot));
        when(simulationRunStateStore.save(
                eq(100L),
                any(RobotState.class)
        )).thenAnswer(invocation -> invocation.getArgument(1));

        SimulationRunResponse response = simulationRunService.start(
                100L,
                AuthenticatedRequester.user(7L)
        );

        assertThat(response.status()).isEqualTo(SimulationRunStatus.RUNNING);
        verify(simulationCommandCycleService).startAfterCommit(100L);
    }

    @Test
    void sharedTemplateRunCannotBeStartedOrReoptimized() {
        User user = user(7L, "user-a@test.com");
        Warehouse template = warehouse(1L, user);
        template.markShared();
        SimulationRun run = SimulationRun.create(template, LocalDateTime.now());
        run.assignUser(user);
        ReflectionTestUtils.setField(run, "id", 100L);
        when(simulationRunRepository.findById(100L)).thenReturn(Optional.of(run));

        assertError(
                () -> simulationRunService.start(
                        100L,
                        AuthenticatedRequester.user(7L)
                ),
                ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
        );
        assertError(
                () -> simulationRunService.validateOwnership(
                        100L,
                        AuthenticatedRequester.user(7L)
                ),
                ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
        );

        verifyNoInteractions(robotRepository, simulationCommandCycleService);
    }

    @Test
    void guestCreatesRunWithOwnGuestPersonalWarehouse() {
        Warehouse warehouse = guestWarehouse(50L, GUEST_A);
        when(warehouseRepository.findById(50L)).thenReturn(Optional.of(warehouse));
        prepareSavedRun();

        simulationRunService.create(
                request(50L),
                AuthenticatedRequester.guest(GUEST_A)
        );

        SimulationRun saved = captureSavedRun();
        assertThat(saved.getWarehouse()).isSameAs(warehouse);
        assertThat(saved.getGuestSessionId()).isEqualTo(GUEST_A);
        assertThat(saved.getUser()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void guestCannotCreateRunWithAnotherGuestsWarehouse() {
        Warehouse warehouse = guestWarehouse(50L, GUEST_B);
        when(warehouseRepository.findById(50L)).thenReturn(Optional.of(warehouse));

        assertError(
                () -> simulationRunService.create(
                        request(50L),
                        AuthenticatedRequester.guest(GUEST_A)
                ),
                ErrorCode.ACCESS_DENIED
        );

        verify(simulationRunRepository, never()).save(any());
    }

    @Test
    void guestCannotCreateRunWithSharedTemplate() {
        Warehouse template = warehouse(1L, user(1L, "template@test.com"));
        template.markShared();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(template));

        assertError(
                () -> simulationRunService.create(
                        request(1L),
                        AuthenticatedRequester.guest(GUEST_A)
                ),
                ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
        );
    }

    @Test
    void guestCannotCreateRunWithUsersWarehouse() {
        Warehouse usersWarehouse = warehouse(60L, user(7L, "owner@test.com"));
        when(warehouseRepository.findById(60L))
                .thenReturn(Optional.of(usersWarehouse));

        assertError(
                () -> simulationRunService.create(
                        request(60L),
                        AuthenticatedRequester.guest(GUEST_A)
                ),
                ErrorCode.ACCESS_DENIED
        );
    }

    @Test
    void userCannotCreateRunWithGuestWarehouse() {
        Warehouse guestWarehouse = guestWarehouse(50L, GUEST_A);
        when(warehouseRepository.findById(50L))
                .thenReturn(Optional.of(guestWarehouse));

        assertError(
                () -> simulationRunService.create(
                        request(50L),
                        AuthenticatedRequester.user(7L)
                ),
                ErrorCode.ACCESS_DENIED
        );
    }

    @Test
    void guestStartsRunWithOwnGuestPersonalWarehouse() {
        Warehouse warehouse = guestWarehouse(50L, GUEST_A);
        SimulationRun run = SimulationRun.create(warehouse, LocalDateTime.now());
        run.assignGuestSession(GUEST_A);
        ReflectionTestUtils.setField(run, "id", 101L);
        when(simulationRunRepository.findById(101L)).thenReturn(Optional.of(run));

        Robot robot = org.mockito.Mockito.mock(Robot.class);
        when(robot.getId()).thenReturn(6L);
        when(robot.getBattery()).thenReturn(100);
        when(robotRepository
                .findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                        50L,
                        RobotAvailabilityStatus.AVAILABLE
                ))
                .thenReturn(List.of(robot));
        when(simulationRunStateStore.save(
                eq(101L),
                any(RobotState.class)
        )).thenAnswer(invocation -> invocation.getArgument(1));

        SimulationRunResponse response = simulationRunService.start(
                101L,
                AuthenticatedRequester.guest(GUEST_A)
        );

        assertThat(response.status()).isEqualTo(SimulationRunStatus.RUNNING);
        verify(simulationCommandCycleService).startAfterCommit(101L);
    }

    @Test
    void guestCannotStartAnotherGuestsRunOrUseItForAiExecution() {
        Warehouse warehouse = guestWarehouse(50L, GUEST_B);
        SimulationRun run = SimulationRun.create(warehouse, LocalDateTime.now());
        run.assignGuestSession(GUEST_B);
        ReflectionTestUtils.setField(run, "id", 102L);
        when(simulationRunRepository.findById(102L)).thenReturn(Optional.of(run));

        assertError(
                () -> simulationRunService.start(
                        102L,
                        AuthenticatedRequester.guest(GUEST_A)
                ),
                ErrorCode.ACCESS_DENIED
        );
        assertError(
                () -> simulationRunService.validateOwnership(
                        102L,
                        AuthenticatedRequester.guest(GUEST_A)
                ),
                ErrorCode.ACCESS_DENIED
        );
        verifyNoInteractions(robotRepository, simulationCommandCycleService);
    }

    private void prepareCreate(Warehouse warehouse, User user) {
        when(warehouseRepository.findById(warehouse.getId()))
                .thenReturn(Optional.of(warehouse));
        when(userRepository.getReferenceById(user.getId())).thenReturn(user);
        prepareSavedRun();
    }

    private void prepareSavedRun() {
        when(simulationRunRepository.save(any(SimulationRun.class)))
                .thenAnswer(invocation -> {
                    SimulationRun run = invocation.getArgument(0);
                    ReflectionTestUtils.setField(run, "id", 100L);
                    return run;
                });
    }

    private SimulationRun captureSavedRun() {
        ArgumentCaptor<SimulationRun> captor =
                ArgumentCaptor.forClass(SimulationRun.class);
        verify(simulationRunRepository).save(captor.capture());
        return captor.getValue();
    }

    private SimulationRunCreateRequest request(Long warehouseId) {
        return new SimulationRunCreateRequest(
                warehouseId,
                1.0,
                null
        );
    }

    private User user(Long id, String email) {
        User user = new User(email, email, "hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Warehouse warehouse(Long id, User owner) {
        Warehouse warehouse = Warehouse.create("warehouse-" + id, 10, 10, owner);
        ReflectionTestUtils.setField(warehouse, "id", id);
        return warehouse;
    }

    private Warehouse guestWarehouse(Long id, String guestSessionId) {
        Warehouse template = warehouse(
                id + 1_000,
                user(id + 1_000, "template-" + id + "@test.com")
        );
        template.markShared();
        Warehouse warehouse = Warehouse.createGuestPersonalCopy(
                template,
                guestSessionId
        );
        ReflectionTestUtils.setField(warehouse, "id", id);
        return warehouse;
    }

    private void assertError(Runnable operation, ErrorCode errorCode) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(errorCode)
                );
    }
}
