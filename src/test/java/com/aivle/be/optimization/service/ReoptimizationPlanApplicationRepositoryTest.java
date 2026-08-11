package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.request.ReoptimizationOptimizationRequest;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.entity.SimulationRunRobot;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:h2:mem:reoptimization-application;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@Import({
        ReoptimizationPlanStagingService.class,
        ReoptimizationPlanApplicationService.class,
        ReoptimizationPlanStagingQueryService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReoptimizationPlanApplicationRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private WarehouseRepository warehouseRepository;
    @Autowired private WarehouseNodeRepository nodeRepository;
    @Autowired private RobotSpecRepository robotSpecRepository;
    @Autowired private RobotRepository robotRepository;
    @Autowired private SimulationRunRepository runRepository;
    @Autowired private SimulationRunRobotRepository participantRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private ReoptimizationPlanStageRepository stageRepository;
    @Autowired private ReoptimizationPlanStagingService stagingService;
    @Autowired private ReoptimizationPlanApplicationService applicationService;
    @Autowired private ReoptimizationPlanStagingQueryService queryService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removeH2ConverterCheckConstraint() {
        /*
         * H2 2.4 closes its in-memory database while evaluating the
         * converter-derived Robot status CHECK constraint. PostgreSQL uses
         * the converter values without this generated test-only constraint.
         */
        jdbcTemplate.queryForList(
                """
                select constraint_name
                from information_schema.table_constraints
                where table_name = 'ROBOT'
                  and constraint_type = 'CHECK'
                """,
                String.class
        ).forEach(name -> jdbcTemplate.execute(
                "alter table robot drop constraint " + name
        ));
    }

    @Test
    void atomicallyAppliesTaskRulesAndReturnsAnActivationReadyView() {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        Task assigned = task(fixture, fixture.oldRobot(), false);
        Task inProgress = task(fixture, fixture.oldRobot(), true);
        Task fullSame = task(fixture, fixture.targetRobot(), true);
        Task toEnd = task(fixture, fixture.carrierRobot(), true);
        Task persistedFullSame = reloadTask(fullSame);
        Task persistedToEnd = reloadTask(toEnd);
        LocalDateTime fullAssignedAt = persistedFullSame.getAssignedAt();
        LocalDateTime fullStartedAt = persistedFullSame.getStartedAt();
        LocalDateTime toEndAssignedAt = persistedToEnd.getAssignedAt();
        LocalDateTime toEndStartedAt = persistedToEnd.getStartedAt();

        String replanId = unique("apply-rules");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(
                        plan(pending, fixture.targetRobot(), 0,
                                TaskPlan.ExecutionStage.FULL),
                        plan(assigned, fixture.targetRobot(), 1,
                                TaskPlan.ExecutionStage.FULL),
                        plan(inProgress, fixture.targetRobot(), 2,
                                TaskPlan.ExecutionStage.FULL),
                        plan(fullSame, fixture.targetRobot(), 3,
                                TaskPlan.ExecutionStage.FULL),
                        plan(toEnd, fixture.carrierRobot(), 0,
                                TaskPlan.ExecutionStage.TO_END)
                ),
                List.of(
                        snapshot(fixture.targetRobot(), null, "PAUSED",
                                ReoptimizationOptimizationRequest.RemainingStage.IDLE),
                        snapshot(fixture.carrierRobot(), toEnd.getId(), "PAUSED",
                                ReoptimizationOptimizationRequest.RemainingStage.TO_END)
                )
        ));

        ReoptimizationActivationPlan result = applicationService.apply(
                fixture.run().getId(), replanId, 7L
        );

        assertThat(result.status())
                .isEqualTo(ReoptimizationPlanStage.Status.DB_APPLIED);
        assertThat(result.taskPlans()).hasSize(5);
        assertThat(result.taskPlans().stream()
                .filter(plan -> plan.taskId().equals(toEnd.getId()))
                .findFirst()).get().satisfies(plan -> {
                    assertThat(plan.pathToStart()).isEmpty();
                    assertThat(plan.pathToEnd()).hasSize(2);
                });
        assertTask(pending, TaskStatus.ASSIGNED, fixture.targetRobot());
        assertTask(assigned, TaskStatus.ASSIGNED, fixture.targetRobot());
        assertTask(inProgress, TaskStatus.ASSIGNED, fixture.targetRobot());
        assertTask(fullSame, TaskStatus.IN_PROGRESS, fixture.targetRobot());
        assertTask(toEnd, TaskStatus.IN_PROGRESS, fixture.carrierRobot());
        assertTimestamps(fullSame, fullAssignedAt, fullStartedAt);
        assertTimestamps(toEnd, toEndAssignedAt, toEndStartedAt);
        assertThat(runRepository.findById(fixture.run().getId()))
                .get().extracting(SimulationRun::getStatus)
                .isEqualTo(SimulationRunStatus.REPLANNING);

        ReoptimizationActivationPlan repeated = applicationService.apply(
                fixture.run().getId(), replanId, 7L
        );
        assertThat(repeated).isEqualTo(result);
        assertTimestamps(fullSame, fullAssignedAt, fullStartedAt);
        assertTimestamps(toEnd, toEndAssignedAt, toEndStartedAt);
        assertThat(queryService.getCurrentDbApplied(fixture.run().getId()).id())
                .isEqualTo(result.stageId());
        assertThat(queryService.getAllDbApplied())
                .extracting(view -> view.id())
                .contains(result.stageId());
    }

    @Test
    void taskStateChangeRollsBackEveryAssignmentAndLeavesStageStaged() {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        Task terminalLater = task(fixture, fixture.oldRobot(), false);
        String replanId = unique("terminal-rollback");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(
                        plan(pending, fixture.targetRobot(), 0,
                                TaskPlan.ExecutionStage.FULL),
                        plan(terminalLater, fixture.targetRobot(), 1,
                                TaskPlan.ExecutionStage.FULL)
                ),
                List.of(snapshot(fixture.targetRobot(), null, "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));
        terminalLater.fail();
        taskRepository.saveAndFlush(terminalLater);

        assertBusinessError(
                () -> applicationService.apply(
                        fixture.run().getId(), replanId, 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_TASK_STATE_CHANGED
        );
        assertTask(pending, TaskStatus.PENDING, null);
        assertThat(stageRepository.findByReplanId(replanId))
                .get().extracting(ReoptimizationPlanStage::getStatus)
                .isEqualTo(ReoptimizationPlanStage.Status.STAGED);
    }

    @Test
    void missingTaskRejectsTheWholeStage() {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        ReoptimizationPlanStageCommand.TaskPlanCommand missing = new ReoptimizationPlanStageCommand.TaskPlanCommand(
                fixture.targetRobot().getId(),
                pending.getId() + 999_999L,
                0,
                TaskPlan.ExecutionStage.FULL,
                null,
                TaskStatus.PENDING.name(),
                fixture.start().getId(),
                fixture.end().getId(),
                1_000L,
                3_000L,
                fullPath(fixture)
        );
        String replanId = unique("missing-task");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(missing),
                List.of(snapshot(fixture.targetRobot(), null, "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));

        assertBusinessError(
                () -> applicationService.apply(
                        fixture.run().getId(), replanId, 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_TASK_STATE_CHANGED
        );
        assertTask(pending, TaskStatus.PENDING, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERROR", "OFFLINE"})
    void rejectsUnavailableRuntimeRobotSnapshots(String runtimeStatus) {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        String replanId = unique("runtime-unavailable");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(plan(pending, fixture.targetRobot(), 0,
                        TaskPlan.ExecutionStage.FULL)),
                List.of(snapshot(fixture.targetRobot(), null, runtimeStatus,
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));

        assertBusinessError(
                () -> applicationService.apply(
                        fixture.run().getId(), replanId, 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );
        assertTask(pending, TaskStatus.PENDING, null);
    }

    @Test
    void rejectsMissingOrDatabaseUnavailableRobot() {
        Fixture missingFixture = fixture();
        Task missingTask = task(missingFixture, null, false);
        Robot nonexistent = Robot.create(
                missingFixture.spec(), missingFixture.warehouse(),
                missingFixture.start().getId(), 100,
                RobotAvailabilityStatus.AVAILABLE
        );
        nonexistent.setId(999_999_999L);
        String missingReplan = unique("missing-robot");
        stagingService.stage(command(
                missingFixture,
                missingReplan,
                List.of(plan(missingTask, nonexistent, 0,
                        TaskPlan.ExecutionStage.FULL)),
                List.of(snapshot(nonexistent, null, "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));
        assertBusinessError(
                () -> applicationService.apply(
                        missingFixture.run().getId(), missingReplan, 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );

        Fixture unavailableFixture = fixture();
        Task unavailableTask = task(unavailableFixture, null, false);
        unavailableFixture.targetRobot().setStatus(
                RobotAvailabilityStatus.UNAVAILABLE
        );
        robotRepository.saveAndFlush(unavailableFixture.targetRobot());
        String unavailableReplan = unique("db-unavailable");
        stagingService.stage(command(
                unavailableFixture,
                unavailableReplan,
                List.of(plan(unavailableTask,
                        unavailableFixture.targetRobot(), 0,
                        TaskPlan.ExecutionStage.FULL)),
                List.of(snapshot(unavailableFixture.targetRobot(), null,
                        "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));
        assertBusinessError(
                () -> applicationService.apply(
                        unavailableFixture.run().getId(), unavailableReplan, 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_ROBOT_INVALID
        );
    }

    @Test
    void rejectsWrongCorrelationWithoutApplyingAnotherStage() {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        String replanId = unique("stale");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(plan(pending, fixture.targetRobot(), 0,
                        TaskPlan.ExecutionStage.FULL)),
                List.of(snapshot(fixture.targetRobot(), null, "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));

        assertBusinessError(
                () -> applicationService.apply(
                        fixture.run().getId(), replanId, 8L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        assertBusinessError(
                () -> applicationService.apply(
                        fixture.run().getId(), unique("other"), 7L
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        assertTask(pending, TaskStatus.PENDING, null);
    }

    @Test
    void requiresNewCommitSurvivesOuterTransactionRollback() {
        Fixture fixture = fixture();
        Task pending = task(fixture, null, false);
        String replanId = unique("requires-new");
        stagingService.stage(command(
                fixture,
                replanId,
                List.of(plan(pending, fixture.targetRobot(), 0,
                        TaskPlan.ExecutionStage.FULL)),
                List.of(snapshot(fixture.targetRobot(), null, "PAUSED",
                        ReoptimizationOptimizationRequest.RemainingStage.IDLE))
        ));
        TransactionTemplate outer = new TransactionTemplate(
                transactionManager
        );

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            applicationService.apply(fixture.run().getId(), replanId, 7L);
            throw new IllegalStateException("rollback outer transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertTask(pending, TaskStatus.ASSIGNED, fixture.targetRobot());
        assertThat(queryService.getCurrentDbApplied(fixture.run().getId())
                .status()).isEqualTo(ReoptimizationPlanStage.Status.DB_APPLIED);
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(new User(
                suffix + "@example.com", "application-test", "hash"
        ));
        Warehouse warehouse = warehouseRepository.save(
                Warehouse.create("warehouse-" + suffix, 10, 10, user)
        );
        WarehouseNode start = nodeRepository.save(WarehouseNode.create(
                warehouse, "A", 0.0, 0.0,
                "start-" + suffix, NodeType.ROUTE,
                WarehouseNode.RouteProperties.empty(), Map.of()
        ));
        WarehouseNode end = nodeRepository.save(WarehouseNode.create(
                warehouse, "A", 1.0, 0.0,
                "end-" + suffix, NodeType.ROUTE,
                WarehouseNode.RouteProperties.empty(), Map.of()
        ));
        RobotSpec spec = robotSpecRepository.save(RobotSpec.create(
                "spec-" + suffix, "TASK", 0.1, 0.2, 0.0
        ));
        Robot oldRobot = robotRepository.save(Robot.create(
                spec, warehouse, start.getId(), 100,
                RobotAvailabilityStatus.AVAILABLE
        ));
        Robot targetRobot = robotRepository.save(Robot.create(
                spec, warehouse, start.getId(), 100,
                RobotAvailabilityStatus.AVAILABLE
        ));
        Robot carrierRobot = robotRepository.save(Robot.create(
                spec, warehouse, start.getId(), 100,
                RobotAvailabilityStatus.AVAILABLE
        ));
        SimulationRun run = SimulationRun.create(
                warehouse, LocalDateTime.now()
        );
        run.start(LocalDateTime.now());
        run.startReplanning();
        run = runRepository.saveAndFlush(run);
        participantRepository.saveAllAndFlush(List.of(
                SimulationRunRobot.create(run, oldRobot),
                SimulationRunRobot.create(run, targetRobot),
                SimulationRunRobot.create(run, carrierRobot)
        ));
        return new Fixture(
                warehouse, spec, run, start, end,
                oldRobot, targetRobot, carrierRobot
        );
    }

    private Task task(Fixture fixture, Robot robot, boolean start) {
        Task task = new Task(
                fixture.warehouse(), fixture.start(), fixture.end(),
                TaskType.OUTBOUND, null, fixture.run()
        );
        if (robot != null) {
            task.assignRobot(robot);
            if (start) {
                task.start();
            }
        }
        return taskRepository.saveAndFlush(task);
    }

    private ReoptimizationPlanStageCommand command(
            Fixture fixture,
            String replanId,
            List<ReoptimizationPlanStageCommand.TaskPlanCommand> plans,
            List<ReoptimizationPlanStageCommand.RobotSnapshotCommand> robots
    ) {
        return new ReoptimizationPlanStageCommand(
                fixture.run().getId(), replanId, 7L,
                "request-" + replanId, 1_000L,
                ReoptimizationReason.MANUAL_REQUEST,
                null, "application test", "validated plan",
                List.of(), robots, plans
        );
    }

    private ReoptimizationPlanStageCommand.TaskPlanCommand plan(
            Task task,
            Robot robot,
            int sequence,
            TaskPlan.ExecutionStage executionStage
    ) {
        return new ReoptimizationPlanStageCommand.TaskPlanCommand(
                robot.getId(),
                task.getId(),
                sequence,
                executionStage,
                task.getRobot() == null ? null : task.getRobot().getId(),
                task.getStatus().name(),
                task.getStartNode().getId(),
                task.getEndNode().getId(),
                1_000L,
                3_000L,
                executionStage == TaskPlan.ExecutionStage.TO_END
                        ? toEndPath(task)
                        : fullPath(task)
        );
    }

    private List<ReoptimizationPlanStageCommand.PathStepCommand> fullPath(
            Task task
    ) {
        return List.of(
                step(ReoptimizationPlanStageCommand.SegmentType.TO_START,
                        0, task.getStartNode().getId(), 1_000L),
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        0, task.getStartNode().getId(), 2_000L),
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        1, task.getEndNode().getId(), 3_000L)
        );
    }

    private List<ReoptimizationPlanStageCommand.PathStepCommand> fullPath(
            Fixture fixture
    ) {
        return List.of(
                step(ReoptimizationPlanStageCommand.SegmentType.TO_START,
                        0, fixture.start().getId(), 1_000L),
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        0, fixture.start().getId(), 2_000L),
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        1, fixture.end().getId(), 3_000L)
        );
    }

    private List<ReoptimizationPlanStageCommand.PathStepCommand> toEndPath(
            Task task
    ) {
        return List.of(
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        0, task.getStartNode().getId(), 1_000L),
                step(ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        1, task.getEndNode().getId(), 3_000L)
        );
    }

    private ReoptimizationPlanStageCommand.PathStepCommand step(
            ReoptimizationPlanStageCommand.SegmentType segment,
            int sequence,
            Long nodeId,
            long time
    ) {
        return new ReoptimizationPlanStageCommand.PathStepCommand(
                segment, sequence, nodeId, time, time
        );
    }

    private ReoptimizationPlanStageCommand.RobotSnapshotCommand snapshot(
            Robot robot,
            Long currentTaskId,
            String status,
            ReoptimizationOptimizationRequest.RemainingStage remainingStage
    ) {
        return new ReoptimizationPlanStageCommand.RobotSnapshotCommand(
                robot.getId(), robot.getNodeId(),
                robot.getBattery().doubleValue(), status,
                currentTaskId, "IDLE", remainingStage
        );
    }

    private void assertTask(
            Task original,
            TaskStatus status,
            Robot robot
    ) {
        Task actual = reloadTask(original);
        assertThat(actual.getStatus()).isEqualTo(status);
        assertThat(actual.getRobot() == null ? null : actual.getRobot().getId())
                .isEqualTo(robot == null ? null : robot.getId());
    }

    private void assertTimestamps(
            Task original,
            LocalDateTime assignedAt,
            LocalDateTime startedAt
    ) {
        Task actual = reloadTask(original);
        assertThat(actual.getAssignedAt()).isEqualTo(assignedAt);
        assertThat(actual.getStartedAt()).isEqualTo(startedAt);
    }

    private Task reloadTask(Task task) {
        return taskRepository.findById(task.getId()).orElseThrow();
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }

    private String unique(String prefix) {
        return UUID.randomUUID().toString();
    }

    private record Fixture(
            Warehouse warehouse,
            RobotSpec spec,
            SimulationRun run,
            WarehouseNode start,
            WarehouseNode end,
            Robot oldRobot,
            Robot targetRobot,
            Robot carrierRobot
    ) {
    }
}
