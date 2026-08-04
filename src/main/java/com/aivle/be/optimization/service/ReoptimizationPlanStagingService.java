package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageCommand;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReoptimizationPlanStagingService {

    private final SimulationRunRepository simulationRunRepository;
    private final ReoptimizationPlanStageRepository stageRepository;

    /**
     * 검증·AI coordinator의 트랜잭션과 분리된 짧은 staging 트랜잭션.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long stage(ReoptimizationPlanStageCommand command) {
        SimulationRun simulationRun = simulationRunRepository
                .findByIdForUpdate(command.simulationRunId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SIMULATION_RUN_NOT_FOUND
                ));

        if (simulationRun.getStatus()
                != SimulationRunStatus.REPLANNING) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STALE
            );
        }

        // 같은 run의 동시 staging은 run lock 뒤에서 다시 직렬화된다.
        rejectDuplicateOrMismatchedReplan(command);

        try {
            ReoptimizationPlanStage stage =
                    ReoptimizationPlanStage.create(
                            simulationRun,
                            command
                    );
            return stageRepository.saveAndFlush(stage).getId();
        } catch (DataAccessException exception) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STAGE_FAILED,
                    exception
            );
        }
    }

    private void rejectDuplicateOrMismatchedReplan(
            ReoptimizationPlanStageCommand command
    ) {
        stageRepository.findByReplanId(command.replanId())
                .ifPresent(existing -> {
                    boolean sameCorrelation = existing
                            .getSimulationRun().getId()
                            .equals(command.simulationRunId())
                            && existing.getSnapshotVersion()
                            .equals(command.snapshotVersion());

                    if (sameCorrelation) {
                        throw new BusinessException(
                                ErrorCode.REOPTIMIZATION_PLAN_STAGE_DUPLICATE
                        );
                    }
                    throw new BusinessException(
                            ErrorCode.REOPTIMIZATION_PLAN_STALE
                    );
                });
    }
}
