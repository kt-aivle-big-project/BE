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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
            if (runtime.isOperationalFailureReview(interactionId)) {
                Long retryMinute = runtime.resolveOperationalFailure(
                        interactionId,
                        request
                );
                dispatchIfAccepted(runtime, retryMinute);
                return runtime.snapshot();
            }
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
            runtime.failForHumanReview(exception.getMessage());
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
        private Map<String, Object> pendingHumanInteraction;
        private boolean operationalFailureReview;
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
            pendingHumanInteraction = null;
            operationalFailureReview = false;
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
                pendingHumanInteraction = value.result().pendingHumanInteraction();
                operationalFailureReview = false;
                humanReviewResponse = null;
                state = CycleState.REVIEW_REQUIRED;
                error = null;
                // Keep inFlight true so another automatic cycle cannot overtake review.
                inFlight = true;
                updatedAt = Instant.now();
                return;
            }
            if (value != null && value.result() != null
                    && value.result().errors() != null
                    && !value.result().errors().isEmpty()) {
                planResponse = value;
                LaroPlanResponse.WorkflowError firstError = value.result().errors().get(0);
                String code = firstError.code() == null || firstError.code().isBlank()
                        ? "PLAN_RESPONSE_ERROR"
                        : firstError.code();
                String message = firstError.message() == null || firstError.message().isBlank()
                        ? "AI 계획 응답에 오류가 포함되어 있습니다."
                        : firstError.message();
                failForHumanReview(code + ": " + message);
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
            String pendingId = pendingInteractionId();
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

        synchronized void failForHumanReview(String message) {
            if (!active) {
                return;
            }
            String normalizedMessage = message == null || message.isBlank()
                    ? "알 수 없는 자동 계획 오류가 발생했습니다."
                    : message.trim();
            String reasonCode = classifyFailure(normalizedMessage, state);
            String interactionId = "CYCLE-ERROR-"
                    + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            boolean transientFailure = isTransientFailure(normalizedMessage);

            Map<String, Object> retryOption = new LinkedHashMap<>();
            retryOption.put("option_id", "RETRY_NOW");
            retryOption.put("label", "다시 시도");
            retryOption.put(
                    "description",
                    "현재 오류를 확인한 뒤 같은 계획 주기를 다시 실행합니다."
            );
            retryOption.put("impact_summary", "검토 즉시 명령 생성과 계획을 다시 시작합니다.");
            retryOption.put("outcome", "RESUME");

            Map<String, Object> terminateOption = new LinkedHashMap<>();
            terminateOption.put("option_id", "TERMINATE_CYCLE");
            terminateOption.put("label", "이번 계획 종료");
            terminateOption.put(
                    "description",
                    "오류가 발생한 계획 주기를 종료하고 현재 실행 상태를 유지합니다."
            );
            terminateOption.put("impact_summary", "다음 주기 전까지 새 계획을 실행하지 않습니다.");
            terminateOption.put("outcome", "TERMINATE");

            Map<String, Object> interaction = new LinkedHashMap<>();
            interaction.put("interaction_id", interactionId);
            interaction.put("kind", "APPROVAL");
            interaction.put("stage", failureStage(state));
            interaction.put("reason_code", reasonCode);
            interaction.put("headline", "자동 계획 오류를 확인해 주세요.");
            interaction.put("prompt", normalizedMessage);
            interaction.put(
                    "context_summary",
                    "오류 이후의 자동 계획은 중지되었습니다. 오류를 확인한 뒤 다시 시도하거나 이번 계획을 종료해 주세요."
            );
            interaction.put("evidence_ids", List.of(
                    "cycle_state=" + state.name(),
                    "planning_mode=" + (planningMode == null ? "UNKNOWN" : planningMode)
            ));
            interaction.put("options", List.of(retryOption, terminateOption));
            interaction.put(
                    "recommended_option_id",
                    transientFailure ? "RETRY_NOW" : "TERMINATE_CYCLE"
            );

            pendingHumanInteraction = Map.copyOf(interaction);
            operationalFailureReview = true;
            state = CycleState.REVIEW_REQUIRED;
            error = normalizedMessage;
            // 검토가 끝나기 전에는 다음 자동 계획 주기가 현재 오류를 추월할 수 없다.
            inFlight = true;
            updatedAt = Instant.now();
        }

        synchronized boolean isOperationalFailureReview(String interactionId) {
            return state == CycleState.REVIEW_REQUIRED
                    && operationalFailureReview
                    && pendingHumanInteraction != null
                    && interactionId != null
                    && interactionId.equals(pendingInteractionId());
        }

        synchronized Long resolveOperationalFailure(
                String interactionId,
                LaroHumanReviewRequest request
        ) {
            requireActive();
            if (executionVersion != request.executionVersion()) {
                throw new IllegalStateException("stale operational review execution version");
            }
            if (!isOperationalFailureReview(interactionId)) {
                throw new IllegalStateException("Operational review does not match the pending cycle");
            }

            String selectedOptionId = request.selectedOptionId();
            boolean terminate = "REJECT".equalsIgnoreCase(request.action())
                    || "CANCEL".equalsIgnoreCase(request.action())
                    || "TERMINATE_CYCLE".equalsIgnoreCase(selectedOptionId);
            if (terminate) {
                state = CycleState.CANCELLED;
                error = null;
                inFlight = false;
                lastTriggeredMinute = Math.max(
                        lastTriggeredMinute,
                        simulatedTimeMs / intervalMs
                );
                humanReviewResponse = new LaroHumanReviewResponse(
                        interactionId,
                        "RESOLVED",
                        "TERMINATED",
                        "오류가 발생한 이번 계획 주기를 종료했습니다.",
                        "CANCELLED",
                        null,
                        null
                );
                updatedAt = Instant.now();
                return null;
            }

            if (!"RETRY_NOW".equalsIgnoreCase(selectedOptionId)
                    && !"APPROVE".equalsIgnoreCase(request.action())) {
                throw new IllegalStateException("Select a valid recovery action");
            }
            long retryMinute = activeCycleMinute;
            String userCommand = cycleUserCommand;
            inFlight = false;
            return accept(retryMinute, userCommand);
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
                    pendingHumanInteraction,
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

        private String pendingInteractionId() {
            if (pendingHumanInteraction != null) {
                Object value = pendingHumanInteraction.get("interaction_id");
                if (value == null) {
                    value = pendingHumanInteraction.get("interactionId");
                }
                if (value != null) {
                    return value.toString();
                }
            }
            return pendingInteractionId(planResponse);
        }

        private static String classifyFailure(String message, CycleState failedState) {
            String normalized = message.toLowerCase();
            if (normalized.contains("504") || normalized.contains("gateway timeout")
                    || normalized.contains("upstream request timeout")) {
                return "AI_GATEWAY_TIMEOUT";
            }
            if (normalized.contains("timeout") || normalized.contains("timed out")) {
                return "AI_REQUEST_TIMEOUT";
            }
            if (normalized.contains("connection") || normalized.contains("connect")) {
                return "AI_CONNECTION_ERROR";
            }
            return switch (failedState) {
                case CHECKING -> "PREFLIGHT_FAILED";
                case GENERATING -> "COMMAND_GENERATION_FAILED";
                case PLANNING, REPLANNING -> "PLAN_REQUEST_FAILED";
                default -> "COMMAND_CYCLE_FAILED";
            };
        }

        private static String failureStage(CycleState failedState) {
            return switch (failedState) {
                case PLANNING, REPLANNING -> "PRE_OPTIMIZATION";
                default -> "PRE_ROUTE";
            };
        }

        private static boolean isTransientFailure(String message) {
            String normalized = message.toLowerCase();
            return normalized.contains("timeout")
                    || normalized.contains("timed out")
                    || normalized.contains("connection")
                    || normalized.contains("502")
                    || normalized.contains("503")
                    || normalized.contains("504");
        }
    }
}
