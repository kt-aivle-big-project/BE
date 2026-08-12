package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandGenerationService;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.dto.LaroHumanReviewRequest;
import com.aivle.be.laro.dto.LaroHumanReviewResponse;
import com.aivle.be.laro.service.LaroPlanService;
import com.aivle.be.laro.service.StaleSimulationExecutionException;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleStatusResponse.CycleState;

/**
 * 시뮬레이션 시각 0분과 실행별 설정 주기 경계마다 새 명령 배치를 생성하고
 * 최초 계획 또는 안전 정지 기반 재계획을 호출한다.
 */
@Service
public class SimulationCommandCycleService {

    private static final Logger log = LoggerFactory.getLogger(SimulationCommandCycleService.class);
    private static final long DEFAULT_INTERVAL_MS = 300_000L;

    private final SimulationRunRepository simulationRunRepository;
    private final FulfillmentCommandGenerationService commandGenerationService;
    private final LaroPlanService laroPlanService;
    private final SimulationPlaybackService playbackService;
    private final SimulationRunPlanSnapshotStore planSnapshotStore;
    private final TaskExecutor taskExecutor;
    private final Map<Long, CycleRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<Long, Object> executionLocks = new ConcurrentHashMap<>();

    public SimulationCommandCycleService(
            SimulationRunRepository simulationRunRepository,
            FulfillmentCommandGenerationService commandGenerationService,
            LaroPlanService laroPlanService,
            SimulationPlaybackService playbackService,
            SimulationRunPlanSnapshotStore planSnapshotStore,
            @Qualifier("simulationCommandCycleExecutor") TaskExecutor taskExecutor
    ) {
        this.simulationRunRepository = simulationRunRepository;
        this.commandGenerationService = commandGenerationService;
        this.laroPlanService = laroPlanService;
        this.playbackService = playbackService;
        this.planSnapshotStore = planSnapshotStore;
        this.taskExecutor = taskExecutor;
    }

    /** 트랜잭션 커밋 뒤 0분 배치를 시작한다. */
    public void startAfterCommit(Long simulationRunId) {
        runAfterCommit(() -> start(simulationRunId));
    }

    public void start(Long simulationRunId) {
        SimulationRun run = findRun(simulationRunId);

        CycleRuntime previous = runtimes.get(simulationRunId);
        long intervalMs = previous == null ? intervalMs(run) : previous.intervalMs();
        FulfillmentCommandGenerateRequest generationRequest = previous == null
                ? FulfillmentCommandGenerateRequest.automatic()
                : previous.generationRequest();
        CycleRuntime runtime = new CycleRuntime(
                simulationRunId,
                run.getExecutionVersion(),
                intervalMs,
                generationRequest,
                true
        );
        runtimes.put(simulationRunId, runtime);
        dispatchIfAccepted(runtime, runtime.acceptScheduledCycle(0));
    }

    public void stop(Long simulationRunId) {
        // reset/stop 뒤 상태 조회가 이전 simulatedTimeMs를 다시 반환하지 않도록
        // 런타임을 비활성화하는 데서 끝내지 않고 저장소에서도 제거한다.
        CycleRuntime runtime = runtimes.remove(simulationRunId);
        if (runtime != null) {
            runtime.stop();
        }
    }

    public SimulationCommandCycleStatusResponse triggerNow(Long simulationRunId) {
        return triggerNow(simulationRunId, null);
    }

    public SimulationCommandCycleStatusResponse triggerNow(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request
    ) {
        SimulationRun run = findRun(simulationRunId);
        if (run.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }
        CycleRuntime runtime = runtimes.computeIfAbsent(
                simulationRunId,
                ignored -> new CycleRuntime(
                        simulationRunId,
                        run.getExecutionVersion(),
                        intervalMs(run),
                        FulfillmentCommandGenerateRequest.automatic(),
                        true
                )
        );
        if (request != null) {
            runtime.configure(request);
        }
        dispatchIfAccepted(runtime, runtime.acceptManualCycle());
        return runtime.snapshot();
    }

    /** 사용자 자연어 의도를 이번 배치에만 주입하고 기존 명령 사이클을 즉시 실행한다. */
    public SimulationCommandCycleStatusResponse triggerUserCommand(
            Long simulationRunId,
            long expectedExecutionVersion,
            String userCommand
    ) {
        SimulationRun run = findRun(simulationRunId);
        if (run.getStatus() != SimulationRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.SIMULATION_RUN_NOT_RUNNING);
        }
        if (run.getExecutionVersion() != expectedExecutionVersion) {
            throw new StaleSimulationExecutionException(
                    simulationRunId,
                    expectedExecutionVersion,
                    run.getExecutionVersion()
            );
        }
        String normalizedCommand = userCommand == null ? "" : userCommand.trim();
        if (normalizedCommand.isEmpty() || normalizedCommand.length() > 4000) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        CycleRuntime runtime = runtimes.computeIfAbsent(
                simulationRunId,
                ignored -> new CycleRuntime(
                        simulationRunId,
                        run.getExecutionVersion(),
                        intervalMs(run),
                        FulfillmentCommandGenerateRequest.automatic(),
                        true
                )
        );
        if (runtime.executionVersion() != expectedExecutionVersion) {
            throw new StaleSimulationExecutionException(
                    simulationRunId,
                    expectedExecutionVersion,
                    runtime.executionVersion()
            );
        }
        Long cycleMinute = runtime.acceptUserCommand(normalizedCommand);
        if (cycleMinute == null) {
            throw new BusinessException(ErrorCode.REOPTIMIZATION_ALREADY_IN_PROGRESS);
        }
        dispatchIfAccepted(runtime, cycleMinute);
        return runtime.snapshot();
    }

    public SimulationCommandCycleStatusResponse configure(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request
    ) {
        SimulationRun run = findRun(simulationRunId);
        CycleRuntime runtime = runtimes.computeIfAbsent(
                simulationRunId,
                ignored -> new CycleRuntime(
                        simulationRunId,
                        run.getExecutionVersion(),
                        intervalMs(run),
                        FulfillmentCommandGenerateRequest.automatic(),
                        run.getStatus() == SimulationRunStatus.RUNNING
                )
        );
        runtime.configure(request);
        return runtime.snapshot();
    }

    public SimulationCommandCycleStatusResponse status(Long simulationRunId) {
        SimulationRun run = findRun(simulationRunId);
        CycleRuntime runtime = runtimes.get(simulationRunId);
        if (runtime == null) {
            return new SimulationCommandCycleStatusResponse(
                    simulationRunId,
                    run.getExecutionVersion(),
                    false,
                    CycleState.IDLE,
                    0,
                    0,
                    0,
                    null,
                    FulfillmentCommandGenerateRequest.automatic().effectiveCommandExpressionMode(),
                    FulfillmentCommandGenerateRequest.automatic().effectivePolicyProfile(),
                    Math.toIntExact(intervalMs(run) / 1_000L),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Instant.now()
            );
        }
        return runtime.snapshot();
    }

    public SimulationCommandCycleStatusResponse respondToHumanReview(
            Long simulationRunId,
            String interactionId,
            LaroHumanReviewRequest request,
            String actorId
    ) {
        SimulationRun run = findRun(simulationRunId);
        if (run.getExecutionVersion() != request.executionVersion()) {
            throw new IllegalStateException(
                    "stale human review: expected executionVersion="
                            + request.executionVersion()
                            + ", actual=" + run.getExecutionVersion()
            );
        }
        CycleRuntime runtime = runtimes.get(simulationRunId);
        if (runtime == null) {
            throw new IllegalStateException("No active command cycle for human review");
        }

        Object executionLock = executionLocks.computeIfAbsent(
                simulationRunId,
                ignored -> new Object()
        );
        synchronized (executionLock) {
            runtime.beginHumanReview(interactionId, request.executionVersion());
            LaroPlanRequest planRequest = runtime.planRequest();
            try {
                LaroHumanReviewResponse response = laroPlanService.respondToHumanReview(
                        simulationRunId,
                        request.executionVersion(),
                        interactionId,
                        request,
                        actorId,
                        planRequest
                );
                runtime.finishHumanReview(response);
                if (response.planResponse() != null) {
                    planSnapshotStore.save(
                            simulationRunId,
                            runtime.activeCycleMinute(),
                            planRequest,
                            response.planResponse()
                    );
                }
                return runtime.snapshot();
            } catch (RuntimeException exception) {
                runtime.failHumanReview(exception.getMessage());
                throw exception;
            }
        }
    }

    public SimulationCommandCycleStatusResponse retryAfterHumanAction(
            Long simulationRunId,
            String interactionId,
            long expectedExecutionVersion
    ) {
        SimulationRun run = findRun(simulationRunId);
        if (run.getExecutionVersion() != expectedExecutionVersion) {
            throw new IllegalStateException("stale human review retry execution version");
        }
        CycleRuntime runtime = runtimes.get(simulationRunId);
        if (runtime == null) {
            throw new IllegalStateException("No held command cycle to retry");
        }
        Long cycleMinute;
        Object executionLock = executionLocks.computeIfAbsent(
                simulationRunId,
                ignored -> new Object()
        );
        synchronized (executionLock) {
            cycleMinute = runtime.retryAfterHumanAction(
                    interactionId,
                    expectedExecutionVersion
            );
        }
        dispatchIfAccepted(runtime, cycleMinute);
        return runtime.snapshot();
    }

    /** 배속이 적용된 시뮬레이션 시계를 전진시키고 분 경계를 감지한다. */
    public void tick() {
        long nowNanos = System.nanoTime();
        for (CycleRuntime runtime : List.copyOf(runtimes.values())) {
            SimulationRun run = simulationRunRepository.findById(runtime.simulationRunId()).orElse(null);
            if (run == null || isTerminated(run.getStatus())) {
                runtime.stop();
                continue;
            }

            long elapsedNanos = runtime.consumeElapsedNanos(nowNanos);
            if (run.getStatus() != SimulationRunStatus.RUNNING) {
                continue;
            }

            double speed = run.getSimulationSpeed() == null ? 1.0 : run.getSimulationSpeed();
            long dueMinute = runtime.advance(elapsedNanos, speed);
            dispatchIfAccepted(runtime, runtime.acceptScheduledCycle(dueMinute));
        }
    }

    private void dispatchIfAccepted(CycleRuntime runtime, Long cycleMinute) {
        if (cycleMinute == null) {
            return;
        }
        taskExecutor.execute(() -> execute(runtime, cycleMinute));
    }

    private void execute(CycleRuntime runtime, long cycleMinute) {
        Object executionLock = executionLocks.computeIfAbsent(
                runtime.simulationRunId(),
                ignored -> new Object()
        );
        synchronized (executionLock) {
            executeLocked(runtime, cycleMinute);
        }
    }

    private void executeLocked(CycleRuntime runtime, long cycleMinute) {
        Long simulationRunId = runtime.simulationRunId();
        try {
            runtime.begin(CycleState.CHECKING, null);
            LaroPreflightResponse preflight = laroPlanService.preflight(simulationRunId);
            if (!preflight.ready()) {
                String problems = preflight.problems() == null
                        ? "preflight not ready"
                        : String.join(", ", preflight.problems());
                throw new IllegalStateException(problems);
            }

            runtime.requireActive();

            runtime.begin(CycleState.GENERATING, null);
            FulfillmentCommandGenerateResponse generated =
                    commandGenerationService.generate(
                            simulationRunId,
                            runtime.activeGenerationRequest()
                    );
            runtime.generated(generated);
            LaroPlanRequest planRequest = withUserCommand(
                    generated.planRequest(),
                    runtime.cycleUserCommand()
            );
            runtime.planRequest(planRequest);

            runtime.requireActive();
            boolean replan = playbackService.hasActiveAiPlan(simulationRunId);
            String planningMode = replan ? "REPLAN" : "INITIAL_PLAN";
            runtime.begin(replan ? CycleState.REPLANNING : CycleState.PLANNING, planningMode);

            LaroPlanResponse response = replan
                    ? laroPlanService.replan(
                            simulationRunId,
                            runtime.executionVersion(),
                            planRequest
                    )
                    : laroPlanService.plan(
                            simulationRunId,
                            runtime.executionVersion(),
                            planRequest
                    );
            runtime.acceptPlanResult(response);

            runtime.requireActive();
            planSnapshotStore.save(simulationRunId, cycleMinute, planRequest, response);

            log.info(
                    "[command-cycle] runId={}, minute={}, mode={}, requestId={} complete",
                    simulationRunId,
                    cycleMinute,
                    planningMode,
                    generated.frontView().requestId()
            );
        } catch (RuntimeException exception) {
            runtime.fail(exception.getMessage());
            log.warn(
                    "[command-cycle] runId={}, minute={} failed: {}",
                    simulationRunId,
                    cycleMinute,
                    exception.getMessage()
            );
        }
    }

    private SimulationRun findRun(Long simulationRunId) {
        return simulationRunRepository.findById(simulationRunId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
    }

    private LaroPlanRequest withUserCommand(
            LaroPlanRequest request,
            String userCommand
    ) {
        if (userCommand == null || userCommand.isBlank()) {
            return request;
        }
        return new LaroPlanRequest(
                request.structuredInput(),
                userCommand,
                request.optimizationBackend(),
                request.runtimeSnapshot()
        );
    }

    private long intervalMs(SimulationRun run) {
        Integer seconds = run.getGenerationIntervalSeconds();
        return seconds == null || seconds <= 0 ? DEFAULT_INTERVAL_MS : seconds * 1_000L;
    }

    private boolean isTerminated(SimulationRunStatus status) {
        return status == SimulationRunStatus.COMPLETED
                || status == SimulationRunStatus.STOPPED
                || status == SimulationRunStatus.FAILED;
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static final class CycleRuntime {
        private final Long simulationRunId;
        private final long executionVersion;
        private long intervalMs;
        private long simulatedTimeMs;
        private long lastTriggeredMinute = -1;
        private long activeCycleMinute;
        private long lastTickNanos = System.nanoTime();
        private boolean active;
        private boolean inFlight;
        private CycleState state = CycleState.IDLE;
        private String planningMode;
        private FulfillmentCommandGenerateRequest generationRequest;
        private FulfillmentCommandGenerateRequest activeGenerationRequest;
        private String cycleUserCommand;
        private FulfillmentCommandGenerateResponse generated;
        private LaroPlanRequest activePlanRequest;
        private LaroPlanResponse planResponse;
        private LaroHumanReviewResponse humanReviewResponse;
        private String error;
        private Instant updatedAt = Instant.now();

        private CycleRuntime(
                Long simulationRunId,
                long executionVersion,
                long intervalMs,
                FulfillmentCommandGenerateRequest generationRequest,
                boolean active
        ) {
            this.simulationRunId = simulationRunId;
            this.executionVersion = executionVersion;
            this.intervalMs = intervalMs;
            this.generationRequest = generationRequest;
            this.activeGenerationRequest = generationRequest;
            this.active = active;
        }

        synchronized Long simulationRunId() {
            return simulationRunId;
        }

        synchronized long executionVersion() {
            return executionVersion;
        }

        synchronized long intervalMs() {
            return intervalMs;
        }

        synchronized FulfillmentCommandGenerateRequest generationRequest() {
            return generationRequest;
        }

        synchronized FulfillmentCommandGenerateRequest activeGenerationRequest() {
            return activeGenerationRequest;
        }

        synchronized String cycleUserCommand() {
            return cycleUserCommand;
        }

        synchronized void configure(FulfillmentCommandGenerateRequest request) {
            generationRequest = request == null
                    ? FulfillmentCommandGenerateRequest.automatic()
                    : request;
            if (generationRequest.generationIntervalSeconds() != null) {
                intervalMs = generationRequest.generationIntervalSeconds() * 1_000L;
                // 변경 시점 다음의 새 주기 경계에서 재계획하도록 버킷을 다시 맞춘다.
                lastTriggeredMinute = simulatedTimeMs / intervalMs;
            }
            updatedAt = Instant.now();
        }

        synchronized long consumeElapsedNanos(long nowNanos) {
            long elapsed = Math.max(0L, nowNanos - lastTickNanos);
            lastTickNanos = nowNanos;
            return elapsed;
        }

        synchronized long advance(long elapsedNanos, double speed) {
            long elapsedMillis = elapsedNanos / 1_000_000L;
            simulatedTimeMs += Math.max(0L, Math.round(elapsedMillis * speed));
            return simulatedTimeMs / intervalMs;
        }

        synchronized Long acceptScheduledCycle(long minute) {
            if (!active || inFlight || minute <= lastTriggeredMinute) {
                return null;
            }
            lastTriggeredMinute = minute;
            return accept(minute, null);
        }

        synchronized Long acceptManualCycle() {
            if (!active || inFlight) {
                return null;
            }
            return accept(simulatedTimeMs / intervalMs, null);
        }

        synchronized Long acceptUserCommand(String userCommand) {
            if (!active || inFlight) {
                return null;
            }
            long minute = simulatedTimeMs / intervalMs;
            lastTriggeredMinute = Math.max(lastTriggeredMinute, minute);
            return accept(minute, userCommand);
        }

        private Long accept(long minute, String userCommand) {
            inFlight = true;
            activeCycleMinute = minute;
            state = CycleState.CHECKING;
            planningMode = null;
            cycleUserCommand = userCommand;
            generated = null;
            activePlanRequest = null;
            planResponse = null;
            humanReviewResponse = null;
            error = null;
            activeGenerationRequest = generationRequest;
            updatedAt = Instant.now();
            return minute;
        }

        synchronized void begin(CycleState nextState, String nextPlanningMode) {
            requireActive();
            state = nextState;
            if (nextPlanningMode != null) {
                planningMode = nextPlanningMode;
            }
            updatedAt = Instant.now();
        }

        synchronized void generated(FulfillmentCommandGenerateResponse value) {
            requireActive();
            generated = value;
            updatedAt = Instant.now();
        }

        synchronized void planRequest(LaroPlanRequest value) {
            requireActive();
            activePlanRequest = value;
            updatedAt = Instant.now();
        }

        synchronized void complete(LaroPlanResponse value) {
            if (!active) {
                return;
            }
            planResponse = value;
            state = CycleState.COMPLETE;
            error = null;
            inFlight = false;
            updatedAt = Instant.now();
        }

        synchronized void acceptPlanResult(LaroPlanResponse value) {
            if (pendingInteractionId(value) != null) {
                planResponse = value;
                humanReviewResponse = null;
                state = CycleState.REVIEW_REQUIRED;
                error = null;
                // Keep inFlight true so another automatic cycle cannot overtake review.
                inFlight = true;
                updatedAt = Instant.now();
                return;
            }
            complete(value);
        }

        synchronized void beginHumanReview(
                String interactionId,
                long expectedExecutionVersion
        ) {
            requireActive();
            if (executionVersion != expectedExecutionVersion) {
                throw new IllegalStateException("stale human review execution version");
            }
            if (state != CycleState.REVIEW_REQUIRED) {
                throw new IllegalStateException("No pending human review for this cycle");
            }
            String pendingId = pendingInteractionId(planResponse);
            if (pendingId == null || !pendingId.equals(interactionId)) {
                throw new IllegalStateException(
                        "Human review interaction does not match the pending cycle"
                );
            }
            state = CycleState.REVIEW_PROCESSING;
            error = null;
            inFlight = true;
            updatedAt = Instant.now();
        }

        synchronized void finishHumanReview(LaroHumanReviewResponse response) {
            humanReviewResponse = response;
            String outcome = response.resumeOutcome() == null
                    ? "FAILED"
                    : response.resumeOutcome().toUpperCase();
            switch (outcome) {
                case "PENDING_REVIEW" -> {
                    if (response.planResponse() == null
                            || pendingInteractionId(response.planResponse()) == null) {
                        fail("LARO returned PENDING_REVIEW without a pending interaction");
                        return;
                    }
                    planResponse = response.planResponse();
                    state = CycleState.REVIEW_REQUIRED;
                    error = null;
                    inFlight = true;
                }
                case "RESUMED" -> {
                    planResponse = response.planResponse();
                    state = CycleState.COMPLETE;
                    error = null;
                    inFlight = false;
                }
                case "HELD" -> {
                    state = CycleState.HELD;
                    error = null;
                    inFlight = true;
                }
                case "TERMINATED" -> {
                    state = CycleState.CANCELLED;
                    error = null;
                    inFlight = false;
                }
                default -> {
                    state = CycleState.ERROR;
                    error = response.message() == null
                            ? "Human review resume failed"
                            : response.message();
                    inFlight = false;
                }
            }
            updatedAt = Instant.now();
        }

        synchronized void failHumanReview(String message) {
            state = CycleState.REVIEW_REQUIRED;
            error = message == null || message.isBlank()
                    ? "Human review request failed"
                    : message;
            inFlight = true;
            updatedAt = Instant.now();
        }

        synchronized Long retryAfterHumanAction(
                String interactionId,
                long expectedExecutionVersion
        ) {
            requireActive();
            if (executionVersion != expectedExecutionVersion) {
                throw new IllegalStateException("stale human review retry execution version");
            }
            if (state != CycleState.HELD || humanReviewResponse == null
                    || !interactionId.equals(humanReviewResponse.interactionId())) {
                throw new IllegalStateException("Human review is not held for this interaction");
            }
            String userCommand = cycleUserCommand;
            inFlight = false;
            return accept(simulatedTimeMs / intervalMs, userCommand);
        }

        synchronized LaroPlanRequest planRequest() {
            if (activePlanRequest == null) {
                throw new IllegalStateException("Human review has no originating plan request");
            }
            return activePlanRequest;
        }

        synchronized long activeCycleMinute() {
            return activeCycleMinute;
        }

        synchronized void fail(String message) {
            if (!active) {
                return;
            }
            state = CycleState.ERROR;
            error = message == null || message.isBlank() ? "command cycle failed" : message;
            inFlight = false;
            updatedAt = Instant.now();
        }

        synchronized void requireActive() {
            if (!active) {
                throw new IllegalStateException("simulation command cycle stopped");
            }
        }

        synchronized void stop() {
            active = false;
            inFlight = false;
            state = CycleState.STOPPED;
            updatedAt = Instant.now();
            lastTickNanos = System.nanoTime();
        }

        synchronized SimulationCommandCycleStatusResponse snapshot() {
            long nextMinute = Math.max(lastTriggeredMinute + 1, simulatedTimeMs / intervalMs + 1);
            return new SimulationCommandCycleStatusResponse(
                    simulationRunId,
                    executionVersion,
                    active,
                    state,
                    simulatedTimeMs,
                    activeCycleMinute,
                    nextMinute * intervalMs,
                    planningMode,
                    generationRequest.effectiveCommandExpressionMode(),
                    generationRequest.effectivePolicyProfile(),
                    Math.toIntExact(intervalMs / 1_000L),
                    generationRequest.averageTasksPerRobot(),
                    cycleUserCommand,
                    generated,
                    planResponse,
                    humanReviewResponse,
                    error,
                    updatedAt
            );
        }

        private static String pendingInteractionId(LaroPlanResponse response) {
            if (response == null || response.result() == null
                    || response.result().pendingHumanInteraction() == null) {
                return null;
            }
            Object value = response.result().pendingHumanInteraction().get("interaction_id");
            if (value == null) {
                value = response.result().pendingHumanInteraction().get("interactionId");
            }
            return value == null ? null : value.toString();
        }
    }
}
