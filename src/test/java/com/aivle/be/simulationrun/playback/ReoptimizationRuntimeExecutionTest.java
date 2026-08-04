package com.aivle.be.simulationrun.playback;

import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.robotstate.domain.RobotStatus;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReoptimizationRuntimeExecutionTest {

    @Test
    void planInstalledFreezesClockEvenWhenDatabaseIsRunning() {
        Fixture fixture = fixture(false);

        fixture.service().tick(500L);

        assertThat(fixture.context().getClockMillis()).isZero();
        assertThat(fixture.robot().getStatus()).isEqualTo(RobotStatus.PAUSED);
    }

    @Test
    void largeTickExecutesEveryAiBoundaryExactlyOnceWithoutPathFinding() {
        Fixture fixture = fixture(true);

        fixture.service().tick(350L);

        verify(fixture.taskService()).startTask(100L);
        verify(fixture.taskService()).completeTask(100L);
        verify(fixture.pathFinder(), never())
                .findPath(anyMap(), anyLong(), anyLong());
        assertThat(fixture.robot().getCurrentNodeId()).isEqualTo(20L);
        assertThat(fixture.robot().isReoptimizationPlanCompleted()).isTrue();
        assertThat(fixture.robot().getStatus()).isEqualTo(RobotStatus.IDLE);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean activate) {
        SimulationRunRepository runRepository =
                mock(SimulationRunRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskService taskService = mock(TaskService.class);
        WarehousePathFinder pathFinder = mock(WarehousePathFinder.class);
        SimulationRun run = mock(SimulationRun.class);
        Task task = mock(Task.class);
        when(run.getStatus()).thenReturn(SimulationRunStatus.RUNNING);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(taskRepository.findById(100L)).thenReturn(Optional.of(task));
        when(task.getStatus()).thenReturn(TaskStatus.ASSIGNED);

        SimulationPlaybackService service = new SimulationPlaybackService(
                runRepository,
                mock(com.aivle.be.simulationrun.repository.SimulationRunStateStore.class),
                taskRepository,
                mock(com.aivle.be.robot.repository.RobotRepository.class),
                mock(com.aivle.be.chargingstation.repository.ChargingStationRepository.class),
                mock(com.aivle.be.warehousenode.repository.WarehouseNodeRepository.class),
                taskService,
                pathFinder,
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class)
        );
        RobotRuntime robot = new RobotRuntime(10L, 10L, 100.0, 1.0, 1.0);
        PlaybackContext context = new PlaybackContext(
                1L, 1L, Map.of(), Map.of(), List.of(robot), List.of(),
                1.0, 1.0, 1.0, 1.0, Map.of()
        );
        context.requestReplanning();
        robot.pauseForReplanning();
        context.areAllRobotsStoppedForReplanning();
        context.bindReplanId(1L, "replan");
        RuntimeTaskPlan taskPlan = new RuntimeTaskPlan(
                100L,
                0,
                TaskPlan.ExecutionStage.FULL,
                List.of(new RuntimePathStep(10L, 0L, 0L)),
                List.of(
                        new RuntimePathStep(10L, 100L, 100L),
                        new RuntimePathStep(20L, 200L, 200L)
                ),
                new RuntimeOperationWindow(10L, 0L, 100L),
                new RuntimeOperationWindow(20L, 200L, 300L),
                0L,
                300L
        );
        context.installReoptimizationPlan(new RuntimeReoptimizationPlan(
                1L, "replan", 1L, 0L,
                List.of(new RuntimeRobotPlan(10L, List.of(taskPlan)))
        ));
        if (activate) {
            context.finishReplanning("replan");
        }
        Map<Long, PlaybackContext> contexts =
                (Map<Long, PlaybackContext>) ReflectionTestUtils.getField(
                        service,
                        "contexts"
                );
        contexts.put(1L, context);
        return new Fixture(service, context, robot, taskService, pathFinder);
    }

    private record Fixture(
            SimulationPlaybackService service,
            PlaybackContext context,
            RobotRuntime robot,
            TaskService taskService,
            WarehousePathFinder pathFinder
    ) {
    }
}
