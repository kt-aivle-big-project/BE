package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.optimization.dto.response.ReoptimizationHistoryResponse;
import com.aivle.be.optimization.repository.OptimizationResultRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReoptimizationQueryService {

    private final SimulationRunRepository simulationRunRepository;
    private final OptimizationResultRepository optimizationResultRepository;

    public List<ReoptimizationHistoryResponse> getHistories(
            Long simulationRunId
    ) {
        if (!simulationRunRepository.existsById(simulationRunId)) {
            throw new BusinessException(
                    ErrorCode.SIMULATION_RUN_NOT_FOUND
            );
        }

        return optimizationResultRepository
                .findAllBySimulationRunIdOrderByCreatedAtDesc(
                        simulationRunId
                )
                .stream()
                .map(ReoptimizationHistoryResponse::from)
                .toList();
    }
}