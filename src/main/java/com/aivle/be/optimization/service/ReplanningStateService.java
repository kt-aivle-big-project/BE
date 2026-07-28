package com.aivle.be.optimization.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReplanningStateService {

    private final SimulationRunRepository simulationRunRepository;

    /**
     * 재계획 시작 상태를 별도 트랜잭션으로 즉시 커밋한다.
     * 커밋 직후 SimulationPlaybackService.tick()이 진행을 멈춘다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startReplanning(Long simulationRunId) {
        SimulationRun simulationRun = findSimulationRun(simulationRunId);

        simulationRun.startReplanning();
    }

    /**
     * 재계획 완료 후 실행 상태로 복구한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishReplanning(Long simulationRunId) {
        SimulationRun simulationRun = findSimulationRun(simulationRunId);

        if (simulationRun.getStatus() == SimulationRunStatus.REPLANNING) {
            simulationRun.finishReplanning();
        }
    }

    private SimulationRun findSimulationRun(Long simulationRunId) {
        return simulationRunRepository
                .findById(simulationRunId)
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.SIMULATION_RUN_NOT_FOUND
                        )
                );
    }
}