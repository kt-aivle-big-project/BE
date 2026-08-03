package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.domain.ReoptimizationReason;
import com.aivle.be.optimization.dto.response.TaskPlan;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageView;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:h2:mem:reoptimization-stage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@Import({
        ReoptimizationPlanStagingService.class,
        ReoptimizationPlanStagingQueryService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReoptimizationPlanStagingRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private SimulationRunRepository simulationRunRepository;

    @Autowired
    private ReoptimizationPlanStageRepository stageRepository;

    @Autowired
    private ReoptimizationPlanStagingService stagingService;

    @Autowired
    private ReoptimizationPlanStagingQueryService queryService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long simulationRunId;

    @BeforeEach
    void setUp() {
        stageRepository.deleteAll();
        simulationRunId = createReplanningRun();
    }

    private Long createReplanningRun() {
        User user = userRepository.save(new User(
                UUID.randomUUID() + "@example.com",
                "stage-test",
                "hash"
        ));
        Warehouse warehouse = warehouseRepository.save(
                Warehouse.create("stage-warehouse", 10, 10, user)
        );
        SimulationRun run = SimulationRun.create(
                warehouse,
                LocalDateTime.now()
        );
        run.start(LocalDateTime.now());
        run.startReplanning();
        return simulationRunRepository.saveAndFlush(run).getId();
    }

    @Test
    void storesAndReadsTheCompleteValidatedPlanInOrder() {
        Long stageId = stagingService.stage(command("replan-complete", 7L));

        ReoptimizationPlanStageView view = queryService.get(
                simulationRunId,
                "replan-complete"
        );
        assertThat(view.id()).isEqualTo(stageId);
        assertThat(view.status())
                .isEqualTo(ReoptimizationPlanStage.Status.STAGED);
        assertThat(view.snapshotVersion()).isEqualTo(7L);
        assertThat(view.requestId()).isEqualTo("request-replan-complete");
        assertThat(view.simulationClockMillis()).isEqualTo(1_000L);
        assertThat(view.reason())
                .isEqualTo(ReoptimizationReason.OBSTACLE_DETECTED);
        assertThat(view.triggerRobotId()).isEqualTo(10L);
        assertThat(view.description()).isEqualTo("stage test");
        assertThat(view.responseMessage()).isEqualTo("validated plan");
        assertThat(view.blockedEdgeIds()).containsExactly(91L, 92L);
        assertThat(view.taskPlans())
                .extracting(ReoptimizationPlanStageView.TaskPlanView::sequence)
                .containsExactly(0, 1);
        assertThat(view.taskPlans())
                .extracting(ReoptimizationPlanStageView.TaskPlanView::taskId)
                .containsExactly(100L, 101L);
        assertThat(view.taskPlans().get(0).pathSteps())
                .extracting(
                        ReoptimizationPlanStageView.PathStepView::segmentType
                )
                .containsExactly(
                        ReoptimizationPlanStageCommand.SegmentType.TO_START,
                        ReoptimizationPlanStageCommand.SegmentType.TO_START,
                        ReoptimizationPlanStageCommand.SegmentType.TO_END,
                        ReoptimizationPlanStageCommand.SegmentType.TO_END
                );
        assertThat(view.taskPlans().get(0).pathSteps())
                .extracting(
                        ReoptimizationPlanStageView.PathStepView::stepSequence
                )
                .containsExactly(0, 1, 0, 1);
        assertThat(view.taskPlans().get(0).pathSteps().get(1))
                .satisfies(step -> {
                    assertThat(step.nodeId()).isEqualTo(20L);
                    assertThat(step.arrivalTimeMillis()).isEqualTo(2_000L);
                    assertThat(step.departureTimeMillis()).isEqualTo(2_500L);
                });
        assertThat(queryService.getCurrentStaged(simulationRunId).id())
                .isEqualTo(stageId);
        assertThat(simulationRunRepository.findById(simulationRunId))
                .get()
                .extracting(SimulationRun::getStatus)
                .isEqualTo(
                        com.aivle.be.simulationrun.domain
                                .SimulationRunStatus.REPLANNING
                );
    }

    @Test
    void blocksDuplicateAndRejectsReplanCorrelationReuse() {
        stagingService.stage(command("replan-duplicate", 7L));

        assertBusinessError(
                () -> stagingService.stage(
                        command("replan-duplicate", 7L)
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_DUPLICATE
        );
        assertBusinessError(
                () -> stagingService.stage(
                        command("replan-duplicate", 8L)
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        Long originalRunId = simulationRunId;
        simulationRunId = createReplanningRun();
        assertBusinessError(
                () -> stagingService.stage(
                        command("replan-duplicate", 7L)
                ),
                ErrorCode.REOPTIMIZATION_PLAN_STALE
        );
        simulationRunId = originalRunId;
        assertThat(stageRepository.count()).isEqualTo(1L);
    }

    @Test
    void reportsMissingStageThroughTheInternalQueryService() {
        assertBusinessError(
                () -> queryService.get(simulationRunId, "missing-replan"),
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_NOT_FOUND
        );
        assertBusinessError(
                () -> queryService.getCurrentStaged(simulationRunId),
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_NOT_FOUND
        );
    }

    @Test
    void rollsBackParentWhenAChildConstraintFails() {
        ReoptimizationPlanStageCommand invalid = commandWithDuplicateStep(
                "replan-child-failure"
        );

        assertBusinessError(
                () -> stagingService.stage(invalid),
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_FAILED
        );
        assertThat(stageRepository.findByReplanId(
                "replan-child-failure"
        )).isEmpty();
    }

    @Test
    void requiresNewStageCommitSurvivesOuterTransactionRollback() {
        TransactionTemplate outer = new TransactionTemplate(
                transactionManager
        );

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            stagingService.stage(command("replan-independent", 7L));
            throw new IllegalStateException("rollback outer transaction");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(stageRepository.findByReplanId("replan-independent"))
                .isPresent();
    }

    private ReoptimizationPlanStageCommand command(
            String replanId,
            Long snapshotVersion
    ) {
        return new ReoptimizationPlanStageCommand(
                simulationRunId,
                replanId,
                snapshotVersion,
                "request-" + replanId,
                1_000L,
                ReoptimizationReason.OBSTACLE_DETECTED,
                10L,
                "stage test",
                "validated plan",
                List.of(91L, 92L),
                List.of(
                        taskPlan(100L, 0, 10L, 20L, 30L, 1_000L),
                        taskPlan(101L, 1, 30L, 40L, 50L, 5_000L)
                )
        );
    }

    private ReoptimizationPlanStageCommand.TaskPlanCommand taskPlan(
            Long taskId,
            int sequence,
            Long origin,
            Long taskStart,
            Long taskEnd,
            long startTime
    ) {
        return new ReoptimizationPlanStageCommand.TaskPlanCommand(
                10L,
                taskId,
                sequence,
                TaskPlan.ExecutionStage.FULL,
                startTime,
                startTime + 3_000L,
                List.of(
                        step(
                                ReoptimizationPlanStageCommand
                                        .SegmentType.TO_START,
                                0,
                                origin,
                                startTime,
                                startTime
                        ),
                        step(
                                ReoptimizationPlanStageCommand
                                        .SegmentType.TO_START,
                                1,
                                taskStart,
                                startTime + 1_000L,
                                startTime + 1_500L
                        ),
                        step(
                                ReoptimizationPlanStageCommand
                                        .SegmentType.TO_END,
                                0,
                                taskStart,
                                startTime + 1_500L,
                                startTime + 2_000L
                        ),
                        step(
                                ReoptimizationPlanStageCommand
                                        .SegmentType.TO_END,
                                1,
                                taskEnd,
                                startTime + 3_000L,
                                startTime + 3_000L
                        )
                )
        );
    }

    private ReoptimizationPlanStageCommand commandWithDuplicateStep(
            String replanId
    ) {
        ReoptimizationPlanStageCommand.PathStepCommand duplicateFirst = step(
                ReoptimizationPlanStageCommand.SegmentType.TO_END,
                0,
                30L,
                2_000L,
                2_000L
        );
        ReoptimizationPlanStageCommand.PathStepCommand duplicateSecond = step(
                ReoptimizationPlanStageCommand.SegmentType.TO_END,
                0,
                30L,
                2_000L,
                2_000L
        );
        ReoptimizationPlanStageCommand.TaskPlanCommand taskPlan =
                new ReoptimizationPlanStageCommand.TaskPlanCommand(
                        10L,
                        100L,
                        0,
                        TaskPlan.ExecutionStage.FULL,
                        1_000L,
                        2_000L,
                        List.of(duplicateFirst, duplicateSecond)
                );
        return new ReoptimizationPlanStageCommand(
                simulationRunId,
                replanId,
                7L,
                "request-" + replanId,
                1_000L,
                ReoptimizationReason.MANUAL_REQUEST,
                null,
                null,
                "invalid child",
                List.of(),
                List.of(taskPlan)
        );
    }

    private ReoptimizationPlanStageCommand.PathStepCommand step(
            ReoptimizationPlanStageCommand.SegmentType segmentType,
            int sequence,
            Long nodeId,
            long arrival,
            long departure
    ) {
        return new ReoptimizationPlanStageCommand.PathStepCommand(
                segmentType,
                sequence,
                nodeId,
                arrival,
                departure
        );
    }

    private void assertBusinessError(
            Runnable action,
            ErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }
}
