package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.entity.ReoptimizationPlanStage;
import com.aivle.be.optimization.repository.ReoptimizationPlanStageRepository;
import com.aivle.be.optimization.staging.ReoptimizationPlanStageView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReoptimizationPlanStagingQueryService {

    private final ReoptimizationPlanStageRepository stageRepository;

    public ReoptimizationPlanStageView get(
            Long simulationRunId,
            String replanId
    ) {
        return stageRepository
                .findBySimulationRun_IdAndReplanId(
                        simulationRunId,
                        replanId
                )
                .map(ReoptimizationPlanStageView::from)
                .orElseThrow(this::notFound);
    }

    public ReoptimizationPlanStageView getCurrentStaged(
            Long simulationRunId
    ) {
        return stageRepository
                .findFirstBySimulationRun_IdAndStatusOrderByCreatedAtDescIdDesc(
                        simulationRunId,
                        ReoptimizationPlanStage.Status.STAGED
                )
                .map(ReoptimizationPlanStageView::from)
                .orElseThrow(this::notFound);
    }

    private BusinessException notFound() {
        return new BusinessException(
                ErrorCode.REOPTIMIZATION_PLAN_STAGE_NOT_FOUND
        );
    }
}
