package com.aivle.be.simulationrun.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.service.RobotStateValidationService;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.simulationrun.controller.request.SimulationRunCreateRequest;
import com.aivle.be.simulationrun.controller.request.SimulationSpeedUpdateRequest;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.simulationrun.repository.SimulationRunStateStore;
import com.aivle.be.task.generation.ScenarioTaskPlanner;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationRunServiceTest {

    private static final String GUEST_A =
            "a4d70ea4-9a96-4c75-8414-24a43114a962";
    private static final String GUEST_B =
            "6877de19-f59f-4913-95e6-32f3fc7ae272";

    @Mock
    private SimulationRunRepository simulationRunRepository;
    @Mock
    private SimulationRunRobotRepository simulationRunRobotRepository;
    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private RobotRepository robotRepository;
    @Mock
    private SimulationRunStateStore simulationRunStateStore;
    @Mock
    private RobotStateValidationService robotStateValidationService;
    @Mock
    private ScenarioRepository scenarioRepository;
    @Mock
    private WarehouseNodeRepository warehouseNodeRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private SimulationPlaybackService simulationPlaybackService;
    @Mock
    private ScenarioTaskPlanner scenarioTaskPlanner;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Spy
    private GuestAccessPolicy guestAccessPolicy = new GuestAccessPolicy();

    @InjectMocks
    private SimulationRunService simulationRunService;

    @Mock
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
        lenient().when(warehouse.getId()).thenReturn(1L);
    }

    @Test
    void userCreationStoresUserAndClearsGuestSession() {
        User user = org.mockito.Mockito.mock(User.class);
        when(userRepository.getReferenceById(7L)).thenReturn(user);
        prepareCreatePersistence();

        simulationRunService.create(
                createRequest(1L, null),
                AuthenticatedRequester.user(7L)
        );

        SimulationRun saved = captureSavedRun();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getGuestSessionId()).isNull();
    }

    @Test
    void guestCreationStoresSessionWithoutUser() {
        Scenario scenario = org.mockito.Mockito.mock(Scenario.class);
        when(scenarioRepository.findById(101L)).thenReturn(Optional.of(scenario));
        when(scenario.getWarehouse()).thenReturn(warehouse);
        prepareCreatePersistence();

        simulationRunService.create(
                createRequest(1L, 101L),
                AuthenticatedRequester.guest(GUEST_A)
        );

        SimulationRun saved = captureSavedRun();
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getGuestSessionId()).isEqualTo(GUEST_A);
        verifyNoInteractions(userRepository);
    }

    @Test
    void guestCreationRejectsNonDemoOrMissingIds() {
        AuthenticatedRequester guest = AuthenticatedRequester.guest(GUEST_A);

        assertAccessDenied(() -> simulationRunService.create(
                createRequest(2L, 1L),
                guest
        ));
        assertAccessDenied(() -> simulationRunService.create(
                createRequest(1L, 2L),
                guest
        ));
        assertAccessDenied(() -> simulationRunService.create(
                createRequest(1L, null),
                guest
        ));
        verifyNoInteractions(warehouseRepository);
    }

    @Test
    void myRunsUsesRequesterSpecificOwnershipColumn() {
        when(simulationRunRepository.findAllByUser_IdOrderByIdDesc(7L))
                .thenReturn(List.of());
        when(simulationRunRepository.findAllByGuestSessionIdOrderByIdDesc(GUEST_A))
                .thenReturn(List.of());

        simulationRunService.getMyRuns(AuthenticatedRequester.user(7L));
        simulationRunService.getMyRuns(AuthenticatedRequester.guest(GUEST_A));

        verify(simulationRunRepository).findAllByUser_IdOrderByIdDesc(7L);
        verify(simulationRunRepository)
                .findAllByGuestSessionIdOrderByIdDesc(GUEST_A);
    }

    @Test
    void guestCanOperateOnlyOwnRunAcrossLifecycle() {
        SimulationRun run = guestRun(10L, GUEST_A);
        when(simulationRunRepository.findById(10L)).thenReturn(Optional.of(run));
        prepareStartDependencies(10L);
        when(taskRepository.findAllBySimulationRun_IdOrderByRequestedAtAsc(10L))
                .thenReturn(List.of());
        when(simulationRunStateStore.findAll(10L)).thenReturn(List.of());
        when(simulationRunRobotRepository
                .findAllBySimulationRun_IdOrderByRobot_Id(10L))
                .thenReturn(List.of());
        AuthenticatedRequester guest = AuthenticatedRequester.guest(GUEST_A);

        simulationRunService.start(10L, guest);
        simulationRunService.pause(10L, guest);
        simulationRunService.resume(10L, guest);
        simulationRunService.changeSpeed(
                10L,
                new SimulationSpeedUpdateRequest(2.0),
                guest
        );
        simulationRunService.getStatus(10L, guest);
        simulationRunService.getParticipants(10L, guest);
        simulationRunService.getRobotStates(10L, guest);
        simulationRunService.reset(10L, guest);
        simulationRunService.stop(10L, guest);

        assertThat(run.getStatus()).isEqualTo(SimulationRunStatus.STOPPED);
        assertThat(run.getSimulationSpeed()).isEqualTo(2.0);
    }

    @Test
    void ownershipMismatchReturnsForbidden() {
        SimulationRun guestRun = guestRun(10L, GUEST_A);
        when(simulationRunRepository.findById(10L))
                .thenReturn(Optional.of(guestRun));

        assertAccessDenied(() -> simulationRunService.getStatus(
                10L,
                AuthenticatedRequester.guest(GUEST_B)
        ));
        assertAccessDenied(() -> simulationRunService.getStatus(
                10L,
                AuthenticatedRequester.user(7L)
        ));

        User owner = org.mockito.Mockito.mock(User.class);
        when(owner.getId()).thenReturn(8L);
        SimulationRun userRun = userRun(11L, owner);
        when(simulationRunRepository.findById(11L))
                .thenReturn(Optional.of(userRun));

        assertAccessDenied(() -> simulationRunService.getStatus(
                11L,
                AuthenticatedRequester.guest(GUEST_A)
        ));
        assertAccessDenied(() -> simulationRunService.getStatus(
                11L,
                AuthenticatedRequester.user(7L)
        ));
    }

    @Test
    void differentGuestSessionsCanStartRunsInSameWarehouse() {
        SimulationRun runA = guestRun(10L, GUEST_A);
        SimulationRun runB = guestRun(11L, GUEST_B);
        when(simulationRunRepository.findById(10L)).thenReturn(Optional.of(runA));
        when(simulationRunRepository.findById(11L)).thenReturn(Optional.of(runB));
        prepareStartDependencies(10L, 11L);

        simulationRunService.start(
                10L,
                AuthenticatedRequester.guest(GUEST_A)
        );
        simulationRunService.start(
                11L,
                AuthenticatedRequester.guest(GUEST_B)
        );

        assertThat(runA.getStatus()).isEqualTo(SimulationRunStatus.RUNNING);
        assertThat(runB.getStatus()).isEqualTo(SimulationRunStatus.RUNNING);
        verify(simulationRunRepository)
                .existsByGuestSessionIdAndStatusInAndIdNot(
                        eq(GUEST_A),
                        any(),
                        eq(10L)
                );
        verify(simulationRunRepository)
                .existsByGuestSessionIdAndStatusInAndIdNot(
                        eq(GUEST_B),
                        any(),
                        eq(11L)
                );
        verify(simulationRunRepository, never())
                .existsByWarehouse_IdAndGuestSessionIdIsNullAndStatusInAndIdNot(
                        anyLong(),
                        any(),
                        anyLong()
                );
    }

    @Test
    void sameGuestSessionCannotStartAnotherActiveRun() {
        SimulationRun run = guestRun(11L, GUEST_A);
        when(simulationRunRepository.findById(11L)).thenReturn(Optional.of(run));
        when(simulationRunRepository
                .existsByGuestSessionIdAndStatusInAndIdNot(
                        eq(GUEST_A),
                        any(),
                        eq(11L)
                ))
                .thenReturn(true);

        assertThatThrownBy(() -> simulationRunService.start(
                11L,
                AuthenticatedRequester.guest(GUEST_A)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.SIMULATION_RUN_ALREADY_ACTIVE));
        verifyNoInteractions(robotRepository);
    }

    @Test
    void userStartKeepsWarehouseLevelActiveRunPolicy() {
        User owner = org.mockito.Mockito.mock(User.class);
        when(owner.getId()).thenReturn(7L);
        SimulationRun run = userRun(12L, owner);
        when(simulationRunRepository.findById(12L)).thenReturn(Optional.of(run));
        prepareStartDependencies(12L);

        simulationRunService.start(
                12L,
                AuthenticatedRequester.user(7L)
        );

        verify(simulationRunRepository)
                .existsByWarehouse_IdAndGuestSessionIdIsNullAndStatusInAndIdNot(
                        eq(1L),
                        any(),
                        eq(12L)
                );
    }

    private void prepareCreatePersistence() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
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

    private void prepareStartDependencies(Long... simulationRunIds) {
        Robot robot = org.mockito.Mockito.mock(Robot.class);
        when(robot.getId()).thenReturn(5L);
        when(robot.getBattery()).thenReturn(100);
        when(robotRepository
                .findAllByWarehouse_IdAndStatusAndNodeIdIsNotNullOrderById(
                        1L,
                        RobotAvailabilityStatus.AVAILABLE
                ))
                .thenReturn(List.of(robot));
        for (Long simulationRunId : simulationRunIds) {
            when(simulationRunStateStore.save(
                    eq(simulationRunId),
                    any(RobotState.class)
            )).thenAnswer(invocation -> invocation.getArgument(1));
        }
    }

    private SimulationRun guestRun(Long id, String guestSessionId) {
        SimulationRun run = SimulationRun.create(warehouse, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", id);
        run.assignGuestSession(guestSessionId);
        return run;
    }

    private SimulationRun userRun(Long id, User user) {
        SimulationRun run = SimulationRun.create(warehouse, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", id);
        run.assignUser(user);
        return run;
    }

    private SimulationRunCreateRequest createRequest(
            Long warehouseId,
            Long scenarioId
    ) {
        return new SimulationRunCreateRequest(
                warehouseId,
                scenarioId,
                1.0,
                null,
                null,
                null
        );
    }

    private void assertAccessDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.ACCESS_DENIED));
    }
}
