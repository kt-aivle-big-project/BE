package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationActivationPlan;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReoptimizationPlanActivationService {

    private final SimulationRunRepository simulationRunRepository;
    private final ReoptimizationPlanStageRepository stageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReoptimizationActivationPlan activate(
            Long simulationRunId,
            String replanId,
            Long snapshotVersion
    ) {
        SimulationRun run = simulationRunRepository
                .findByIdForUpdate(simulationRunId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SIMULATION_RUN_NOT_FOUND
                ));
        ReoptimizationPlanStage stage = stageRepository
                .findExactForUpdate(simulationRunId, replanId, snapshotVersion)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REOPTIMIZATION_PLAN_STAGE_NOT_FOUND
                ));

        if (stage.getStatus() == ReoptimizationPlanStage.Status.ACTIVATED
                && run.getStatus() == SimulationRunStatus.RUNNING) {
            return ReoptimizationActivationPlan.from(stage);
        }
        if (stage.getStatus()
                != ReoptimizationPlanStage.Status.DB_APPLIED
                || run.getStatus() != SimulationRunStatus.REPLANNING) {
            throw new BusinessException(
                    ErrorCode.REOPTIMIZATION_PLAN_STAGE_INVALID_STATUS
            );
        }

        stage.markActivated();
        run.finishReplanning();
        stageRepository.flush();
        simulationRunRepository.flush();
        return ReoptimizationActivationPlan.from(stage);
    }
}
