package com.aivle.be.laro.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.client.LaroPlanClient;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.dto.LaroHumanReviewRequest;
import com.aivle.be.laro.dto.LaroHumanReviewResponse;
import com.aivle.be.laro.dto.LaroLowBatteryContext;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LaroPlanService {
    private static final Logger log = LoggerFactory.getLogger(LaroPlanService.class);

    private final LaroPlanClient client;
    private final LaroPlanExecutionService executionService;
    private final LaroReplanStateService replanStateService;
    private final SimulationPlaybackService playbackService;
    private final LaroInventoryReservationService inventoryReservationService;
    private final SimulationRunRepository simulationRunRepository;
    private final long safeNodeWaitTimeoutMs;

    public LaroPlanService(
            LaroPlanClient client,
            LaroPlanExecutionService executionService,
            LaroReplanStateService replanStateService,
            SimulationPlaybackService playbackService,
            LaroInventoryReservationService inventoryReservationService,
            SimulationRunRepository simulationRunRepository,
            @Value("${laro.replan.safe-node-timeout-ms:60000}") long safeNodeWaitTimeoutMs
    ) {
        this.client = client;
        this.executionService = executionService;
        this.replanStateService = replanStateService;
        this.playbackService = playbackService;
        this.inventoryReservationService = inventoryReservationService;
        this.simulationRunRepository = simulationRunRepository;
        this.safeNodeWaitTimeoutMs = safeNodeWaitTimeoutMs;
    }

    public LaroPreflightResponse preflight(Long simulationRunId) {
        return client.preflight(simulationRunId);
    }

    public void holdForHumanReview(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        SimulationRunStatus status = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND))
                .getStatus();
        if (status == SimulationRunStatus.PAUSED) {
            return;
        }

        if (playbackService.hasActiveAiPlan(simulationRunId)) {
            try {
                if (status == SimulationRunStatus.RUNNING) {
                    playbackService.beginQuiescing(simulationRunId);
                    replanStateService.startQuiescing(simulationRunId);
                    awaitSafeNodes(simulationRunId, expectedExecutionVersion);
                } else if (status == SimulationRunStatus.QUIESCING) {
                    awaitSafeNodes(simulationRunId, expectedExecutionVersion);
                } else if ((status == SimulationRunStatus.REPLANNING
                        || status == SimulationRunStatus.PENDING_ACTIVATION)
                        && !playbackService.isReadyForReplanRequest(simulationRunId)) {
                    awaitSafeNodes(simulationRunId, expectedExecutionVersion);
                }
            } finally {
                replanStateService.pauseForHumanReview(simulationRunId);
            }
            return;
        }
        replanStateService.pauseForHumanReview(simulationRunId);
    }

    public void resumeForHumanReviewDecision(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        replanStateService.resumeFromHumanReview(simulationRunId);
    }

    public void cancelHumanReviewHold(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        if (!playbackService.hasActiveAiPlan(simulationRunId)) {
            return;
        }
        replanStateService.resumeFromHumanReview(simulationRunId);
        playbackService.cancelQuiescing(simulationRunId);
    }

    public LaroHumanReviewResponse respondToHumanReview(
            Long simulationRunId,
            long expectedExecutionVersion,
            String interactionId,
            LaroHumanReviewRequest reviewRequest,
            String actorId,
            LaroPlanRequest planRequest
    ) {
        validateExecutableWarehouse(simulationRunId);
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        LaroHumanReviewResponse response = client.respondToHumanReview(
                simulationRunId,
                interactionId,
                reviewRequest,
                actorId
        );
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        if (response.planResponse() != null) {
            applyHumanReviewPlan(
                    simulationRunId,
                    expectedExecutionVersion,
                    planRequest,
                    response.planResponse()
            );
        } else if (isTerminalWithoutPlan(response)) {
            restorePreviousPlan(simulationRunId);
        }
        return response;
    }

    public LaroPlanResponse plan(Long simulationRunId, LaroPlanRequest request) {
        validateExecutableWarehouse(simulationRunId);
        return plan(
                simulationRunId,
                currentExecutionVersion(simulationRunId),
                request
        );
    }

    public LaroPlanResponse plan(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request
    ) {
        validateExecutableWarehouse(simulationRunId);
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        LaroPlanResponse response = client.plan(simulationRunId, request);
        try {
            executionService.activateIfReady(
                    simulationRunId,
                    expectedExecutionVersion,
                    request,
                    response
            );
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            throw exception;
        }
    }

    public LaroPlanResponse replay(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        try {
            executionService.activateIfReady(
                    simulationRunId,
                    expectedExecutionVersion,
                    request,
                    response
            );
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            throw exception;
        }
    }

    public LaroPlanResponse applyHumanReviewPlan(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        validateExecutableWarehouse(simulationRunId);
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        if (!isReady(response)) {
            return response;
        }

        boolean replan = response.result().plan().planKind() != null
                && "REPLAN".equalsIgnoreCase(response.result().plan().planKind())
                && playbackService.hasActiveAiPlan(simulationRunId);
        if (!replan) {
            executionService.activateIfReady(
                    simulationRunId,
                    expectedExecutionVersion,
                    request,
                    response
            );
            return response;
        }

        try {
            prepareReplanAtSafeNodes(
                    simulationRunId,
                    expectedExecutionVersion
            );
            requireCurrentExecution(simulationRunId, expectedExecutionVersion);
            replanStateService.waitForActivation(simulationRunId);
            boolean staged = executionService.stageReplanIfReady(
                    simulationRunId,
                    expectedExecutionVersion,
                    request,
                    response
            );
            if (!staged) {
                playbackService.cancelQuiescing(simulationRunId);
                replanStateService.restoreRunning(simulationRunId);
            }
            return response;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            if (isCurrentExecution(simulationRunId, expectedExecutionVersion)) {
                playbackService.cancelQuiescing(simulationRunId);
                restoreRunningQuietly(simulationRunId);
            }
            throw exception;
        }
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request
    ) {
        return replan(
                simulationRunId,
                expectedExecutionVersion,
                request,
                "NEW_ORDER"
        );
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            String reason
    ) {
        return replan(
                simulationRunId,
                expectedExecutionVersion,
                request,
                reason,
                null
        );
    }

    public LaroPlanResponse replan(
            Long simulationRunId,
            long expectedExecutionVersion,
            LaroPlanRequest request,
            String reason,
            LaroLowBatteryContext lowBatteryContext
    ) {
        validateExecutableWarehouse(simulationRunId);
        requireCurrentExecution(simulationRunId, expectedExecutionVersion);
        LaroPlanResponse response = null;
        try {
            playbackService.activeAiPlan(simulationRunId);
            prepareReplanAtSafeNodes(
                    simulationRunId,
                    expectedExecutionVersion
            );
            SimulationPlaybackService.ActiveAiPlan active = playbackService.activeAiPlan(simulationRunId);
            if (lowBatteryContext != null) {
                response = client.replan(
                        simulationRunId,
                        active.planId(),
                        active.planVersion(),
                        active.clockMillis(),
                        request,
                        reason,
                        lowBatteryContext
                );
            } else if (reason == null || reason.isBlank()
                    || "NEW_ORDER".equalsIgnoreCase(reason)) {
                response = client.replan(
                        simulationRunId,
                        active.planId(),
                        active.planVersion(),
                        active.clockMillis(),
                        request
                );
            } else {
                response = client.replan(
                        simulationRunId,
                        active.planId(),
                        active.planVersion(),
                        active.clockMillis(),
                        request,
                        reason
                );
            }
            logReplanResponse(
                    simulationRunId,
                    expectedExecutionVersion,
                    reason,
                    lowBatteryContext,
                    request,
                    active,
                    response
            );
            requireCurrentExecution(simulationRunId, expectedExecutionVersion);
            if (!isReady(response)) {
                if (hasPendingHumanReview(response)) {
                    return response;
                }
                playbackService.cancelQuiescing(simulationRunId);
                replanStateService.restoreRunning(simulationRunId);
                return response;
            }
            replanStateService.waitForActivation(simulationRunId);
            boolean staged = executionService.stageReplanIfReady(
                    simulationRunId,
                    expectedExecutionVersion,
                    request,
                    response
            );
            if (!staged) {
                failCandidatePlan(simulationRunId, response);
                playbackService.cancelQuiescing(simulationRunId);
                replanStateService.restoreRunning(simulationRunId);
                return response;
            }
            return response;
        } catch (StaleSimulationExecutionException exception) {
            failCandidatePlan(simulationRunId, response);
            throw exception;
        } catch (RuntimeException exception) {
            failCandidatePlan(simulationRunId, response);
            if (isCurrentExecution(simulationRunId, expectedExecutionVersion)) {
                playbackService.cancelQuiescing(simulationRunId);
                restoreRunningQuietly(simulationRunId);
            }
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

    private void awaitSafeNodes(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        long deadline = System.nanoTime() + safeNodeWaitTimeoutMs * 1_000_000L;
        while (!playbackService.isReadyForReplanRequest(simulationRunId)) {
            requireCurrentExecution(simulationRunId, expectedExecutionVersion);
            if (!playbackService.hasActiveAiPlan(simulationRunId)) {
                throw new IllegalStateException(
                        "Active AI playback context disappeared while waiting for safe nodes"
                );
            }
            if (System.nanoTime() >= deadline) {
                List<SimulationPlaybackService.ReplanBarrierRobotStatus> barrier =
                        playbackService.replanBarrierStatus(simulationRunId);
                log.error(
                        "[LARO replan] safe-node barrier timeout: runId={}, timeoutMs={}, robots={}",
                        simulationRunId,
                        safeNodeWaitTimeoutMs,
                        barrier
                );
                throw new IllegalStateException(
                        "Timed out while waiting for robots to finish their current tasks "
                                + "and reach safe nodes: " + barrier
                );
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

    private void logReplanResponse(
            Long simulationRunId,
            long expectedExecutionVersion,
            String reason,
            LaroLowBatteryContext lowBatteryContext,
            LaroPlanRequest request,
            SimulationPlaybackService.ActiveAiPlan active,
            LaroPlanResponse response
    ) {
        LaroPlanResponse.Result result = response == null ? null : response.result();
        LaroPlanResponse.SimulationPlan plan = result == null ? null : result.plan();
        List<LaroPlanResponse.RobotPlan> robots = plan == null || plan.robots() == null
                ? List.of() : plan.robots();
        int stepCount = robots.stream()
                .map(LaroPlanResponse.RobotPlan::steps)
                .filter(steps -> steps != null)
                .mapToInt(List::size)
                .sum();
        String affectedRobotCode = lowBatteryContext == null
                ? null : "R" + lowBatteryContext.robotId();
        LaroPlanResponse.RobotPlan affectedRobotPlan = affectedRobotCode == null
                ? null
                : robots.stream()
                        .filter(robot -> affectedRobotCode.equals(robot.robotId()))
                        .findFirst()
                        .orElse(null);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("simulationRunId", simulationRunId);
        values.put("executionVersion", expectedExecutionVersion);
        values.put("reason", reason);
        values.put("requestId", request == null || request.structuredInput() == null
                ? null : request.structuredInput().requestId());
        values.put("requestOperationCount", request == null
                || request.structuredInput() == null
                || request.structuredInput().operations() == null
                ? 0 : request.structuredInput().operations().size());
        values.put("activePlanId", active == null ? null : active.planId());
        values.put("activePlanVersion", active == null ? null : active.planVersion());
        values.put("replanAtSimTimeMs", active == null ? null : active.clockMillis());
        values.put("lowBatteryContext", lowBatteryContext);
        values.put("responseRequestId", response == null ? null : response.requestId());
        values.put("responseSimulationRunId", response == null
                ? null : response.simulationRunId());
        values.put("responseWarehouseNumericId", response == null
                ? null : response.warehouseNumericId());
        values.put("resultStatus", result == null ? null : result.status());
        values.put("requestMode", result == null ? null : result.requestMode());
        values.put("finalRoute", result == null ? null : result.finalRoute());
        values.put("effectivePlanningMode", result == null
                ? null : result.effectivePlanningMode());
        values.put("routerLlmExecuted", result == null
                ? null : result.routerLlmExecuted());
        values.put("planId", plan == null ? null : plan.planId());
        values.put("planVersion", plan == null ? null : plan.planVersion());
        values.put("basePlanId", plan == null ? null : plan.basePlanId());
        values.put("planStatus", plan == null ? null : plan.status());
        values.put("planKind", plan == null ? null : plan.planKind());
        values.put("effectiveFromSimTimeMs", plan == null
                ? null : plan.effectiveFromSimTimeMs());
        values.put("makespanMs", plan == null ? null : plan.makespanMs());
        values.put("absoluteFinishAtMs", plan == null
                ? null : plan.absoluteFinishAtMs());
        values.put("robotCount", robots.size());
        values.put("stepCount", stepCount);
        values.put("logicalOperationCount", plan == null
                || plan.logicalOperations() == null
                ? 0 : plan.logicalOperations().size());
        values.put("handoverPoints", plan == null
                || plan.handoverPoints() == null
                ? List.of() : plan.handoverPoints());
        values.put("stationReservations", plan == null
                || plan.stationReservations() == null
                ? List.of() : plan.stationReservations());
        values.put("affectedRobotPlan", summarizeAffectedRobot(affectedRobotPlan));
        values.put("workflowErrors", result == null || result.errors() == null
                ? List.of() : result.errors());
        log.info("[LARO replan diagnostic] {}", values);
    }

    private Map<String, Object> summarizeAffectedRobot(
            LaroPlanResponse.RobotPlan robot
    ) {
        if (robot == null) {
            return Map.of();
        }
        List<LaroPlanResponse.PlanStep> steps = robot.steps() == null
                ? List.of() : robot.steps();
        int fromIndex = Math.max(0, steps.size() - 8);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("robotId", robot.robotId());
        values.put("initialNode", robot.initialNode());
        values.put("availableAtMs", robot.availableAtMs());
        values.put("finishAtMs", robot.finishAtMs());
        values.put("stepCount", steps.size());
        values.put("terminalSteps", steps.subList(fromIndex, steps.size()));
        return values;
    }

    private boolean hasPendingHumanReview(LaroPlanResponse response) {
        return response != null
                && response.result() != null
                && response.result().pendingHumanInteraction() != null
                && !response.result().pendingHumanInteraction().isEmpty();
    }

    private boolean isTerminalWithoutPlan(LaroHumanReviewResponse response) {
        if (response == null || response.resumeOutcome() == null) {
            return false;
        }
        return "TERMINATED".equalsIgnoreCase(response.resumeOutcome())
                || "FAILED".equalsIgnoreCase(response.resumeOutcome());
    }

    private void prepareReplanAtSafeNodes(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        SimulationRunStatus status = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND))
                .getStatus();
        switch (status) {
            case RUNNING -> {
                playbackService.beginQuiescing(simulationRunId);
                replanStateService.startQuiescing(simulationRunId);
                awaitSafeNodes(simulationRunId, expectedExecutionVersion);
                replanStateService.startReplanning(simulationRunId);
            }
            case QUIESCING -> {
                awaitSafeNodes(simulationRunId, expectedExecutionVersion);
                replanStateService.startReplanning(simulationRunId);
            }
            case REPLANNING -> awaitSafeNodes(
                    simulationRunId,
                    expectedExecutionVersion
            );
            default -> throw new BusinessException(
                    ErrorCode.INVALID_SIMULATION_RUN_TRANSITION
            );
        }
    }

    private void restorePreviousPlan(Long simulationRunId) {
        if (!playbackService.hasActiveAiPlan(simulationRunId)) {
            return;
        }
        SimulationRunStatus status = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND))
                .getStatus();
        if (status == SimulationRunStatus.RUNNING) {
            playbackService.cancelQuiescing(simulationRunId);
            return;
        }
        if (status != SimulationRunStatus.QUIESCING
                && status != SimulationRunStatus.REPLANNING
                && status != SimulationRunStatus.PENDING_ACTIVATION) {
            return;
        }
        playbackService.cancelQuiescing(simulationRunId);
        replanStateService.restoreRunning(simulationRunId);
    }

    public LaroPlanResponse replan(Long simulationRunId, LaroPlanRequest request) {
        validateExecutableWarehouse(simulationRunId);
        return replan(
                simulationRunId,
                currentExecutionVersion(simulationRunId),
                request
        );
    }

    private void requireCurrentExecution(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        long actualExecutionVersion = simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND))
                .getExecutionVersion();
        if (actualExecutionVersion != expectedExecutionVersion) {
            throw new StaleSimulationExecutionException(
                    simulationRunId,
                    expectedExecutionVersion,
                    actualExecutionVersion
            );
        }
    }

    private long currentExecutionVersion(Long simulationRunId) {
        return simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND))
                .getExecutionVersion();
    }

    private boolean isCurrentExecution(
            Long simulationRunId,
            long expectedExecutionVersion
    ) {
        return simulationRunRepository.findById(simulationRunId)
                .map(run -> run.getExecutionVersion() == expectedExecutionVersion)
                .orElse(false);
    }

    private void restoreRunningQuietly(Long simulationRunId) {
        try {
            replanStateService.restoreRunning(simulationRunId);
        } catch (RuntimeException ignored) {
            // Preserve the original replan failure.
        }
    }

    private void validateExecutableWarehouse(Long simulationRunId) {
        boolean shared = simulationRunRepository.findByIdWithWarehouse(
                        simulationRunId
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SIMULATION_RUN_NOT_FOUND
                ))
                .getWarehouse()
                .isShared();
        if (shared) {
            throw new BusinessException(
                    ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
            );
        }
    }
}
