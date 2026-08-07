package com.aivle.be.laro.service;

import com.aivle.be.laro.client.LaroPlanClient;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LaroPlanService {
    private final LaroPlanClient client;
    private final LaroPlanExecutionService executionService;
    private final LaroReplanStateService replanStateService;
    private final SimulationPlaybackService playbackService;
    private final LaroInventoryReservationService inventoryReservationService;
    private final long safeNodeWaitTimeoutMs;

    public LaroPlanService(
            LaroPlanClient client,
            LaroPlanExecutionService executionService,
            LaroReplanStateService replanStateService,
            SimulationPlaybackService playbackService,
            LaroInventoryReservationService inventoryReservationService,
            @Value("${laro.replan.safe-node-timeout-ms:30000}") long safeNodeWaitTimeoutMs
    ) {
        this.client = client;
        this.executionService = executionService;
        this.replanStateService = replanStateService;
        this.playbackService = playbackService;
        this.inventoryReservationService = inventoryReservationService;
        this.safeNodeWaitTimeoutMs = safeNodeWaitTimeoutMs;
    }

    public LaroPreflightResponse preflight(Long simulationRunId) {
        return client.preflight(simulationRunId);
    }

    public LaroPlanResponse plan(Long simulationRunId, LaroPlanRequest request) {
        LaroPlanResponse response = client.plan(simulationRunId, request);
        try {
            LaroPlanExecutionService.PreparedExecution prepared =
                    executionService.prepareIfReady(simulationRunId, request, response);
            if (prepared != null) {
                executionService.activatePrepared(prepared);
            }
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            throw exception;
        }
    }

    /**
     * 이미 저장된 계획을 AI 호출 없이 그대로 다시 실행한다.
     *
     * <p>초기화 후 재시작할 때 쓴다. {@link #plan}과 같은 반영 절차를 타되
     * {@code client.plan} 만 건너뛰므로, 처음 실행과 완전히 같은 계획이 돈다.
     */
    public LaroPlanResponse replay(
            Long simulationRunId,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        try {
            LaroPlanExecutionService.PreparedExecution prepared =
                    executionService.prepareIfReady(simulationRunId, request, response);
            if (prepared != null) {
                executionService.activatePrepared(prepared);
            }
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            throw exception;
        }
    }

    public LaroPlanResponse replan(Long simulationRunId, LaroPlanRequest request) {
        LaroPlanResponse response = null;
        try {
            playbackService.activeAiPlan(simulationRunId);
            playbackService.beginQuiescing(simulationRunId);
            replanStateService.startQuiescing(simulationRunId);
            awaitSafeNodes(simulationRunId);
            replanStateService.startReplanning(simulationRunId);
            SimulationPlaybackService.ActiveAiPlan active = playbackService.activeAiPlan(simulationRunId);
            response = client.replan(
                    simulationRunId,
                    active.planId(),
                    active.planVersion(),
                    active.clockMillis(),
                    request
            );
            if (!isReady(response)) {
                playbackService.cancelQuiescing(simulationRunId);
                replanStateService.restoreRunning(simulationRunId);
                return response;
            }
            replanStateService.waitForActivation(simulationRunId);
            LaroPlanExecutionService.PreparedExecution prepared =
                    executionService.prepareIfReady(simulationRunId, request, response);
            if (prepared == null) {
                failCandidatePlan(simulationRunId, response);
                playbackService.cancelQuiescing(simulationRunId);
                replanStateService.restoreRunning(simulationRunId);
                return response;
            }
            executionService.stagePrepared(prepared);
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            playbackService.cancelQuiescing(simulationRunId);
            restoreRunningQuietly(simulationRunId);
            throw exception;
        }
    }

    private void failCandidatePlan(Long simulationRunId, LaroPlanResponse response) {
        if (response == null || response.result() == null
                || response.result().plan() == null) {
            return;
        }
        inventoryReservationService.failAndReleasePlan(
                simulationRunId, response.result().plan().planId());
    }

    private void awaitSafeNodes(Long simulationRunId) {
        long deadline = System.nanoTime() + safeNodeWaitTimeoutMs * 1_000_000L;
        while (!playbackService.isReadyForReplanRequest(simulationRunId)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Timed out while waiting for robots to reach safe nodes");
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for safe nodes", exception);
            }
        }
    }

    private boolean isReady(LaroPlanResponse response) {
        return response != null && response.result() != null
                && response.result().plan() != null
                && "READY".equalsIgnoreCase(response.result().plan().status());
    }

    private void restoreRunningQuietly(Long simulationRunId) {
        try {
            replanStateService.restoreRunning(simulationRunId);
        } catch (RuntimeException ignored) {
            // Preserve the original replan failure.
        }
    }
}
