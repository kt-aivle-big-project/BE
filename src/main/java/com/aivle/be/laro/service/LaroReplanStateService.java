package com.aivle.be.laro.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.controller.response.SimulationRunResponse;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LaroReplanStateService {

    private static final String RUN_TOPIC = "/topic/simulation-runs";

    private final SimulationRunRepository simulationRunRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void startQuiescing(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        run.startQuiescing();
        broadcast(run);
    }

    @Transactional
    public void startReplanning(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        run.startReplanning();
        broadcast(run);
    }

    @Transactional
    public void waitForActivation(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        run.waitForPlanActivation();
        broadcast(run);
    }

    @Transactional
    public void restoreRunning(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        run.finishReplanning();
        broadcast(run);
    }

    @Transactional
    public void pauseForHumanReview(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        if (run.getStatus() == SimulationRunStatus.PAUSED) {
            return;
        }
        run.pauseForHumanReview(LocalDateTime.now());
        broadcast(run);
    }

    @Transactional
    public void resumeFromHumanReview(Long simulationRunId) {
        SimulationRun run = find(simulationRunId);
        if (run.getStatus() != SimulationRunStatus.PAUSED) {
            return;
        }
        run.resume();
        broadcast(run);
    }

    private SimulationRun find(Long simulationRunId) {
        return simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
    }

    private void broadcast(SimulationRun run) {
        messagingTemplate.convertAndSend(RUN_TOPIC, SimulationRunResponse.from(run));
    }
}
