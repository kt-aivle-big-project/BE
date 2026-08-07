package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.fulfillmentcommand.controller.response.FulfillmentCommandGenerateResponse;
import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandGenerationService;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPreflightResponse;
import com.aivle.be.laro.service.LaroPlanService;
import com.aivle.be.simulationrun.domain.SimulationRunStatus;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.entity.SimulationRunPlanSnapshot;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.aivle.be.simulationrun.commandcycle.SimulationCommandCycleStatusResponse.CycleState;

/**
 * 시뮬레이션 시각 0분, 5분, 10분 ... 경계마다 새 명령 배치를 생성하고
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

        // 초기화 뒤 다시 시작한 경우다. 저장된 계획이 있으면 주기마다
        // 그걸 그대로 재생하고 AI 는 부르지 않는다.
        // (새 실행은 스냅샷이 없으므로 평소대로 새 계획을 만든다)
        if (planSnapshotStore.hasSnapshot(simulationRunId)) {
            log.info(
                    "[command-cycle] runId={} 저장된 계획으로 재생합니다. (AI 호출 없음)",
                    simulationRunId
            );
        }

        long intervalMs = intervalMs(run);
        CycleRuntime previous = runtimes.get(simulationRunId);
        FulfillmentCommandGenerateRequest generationRequest = previous == null
                ? FulfillmentCommandGenerateRequest.automatic()
                : previous.generationRequest();
        CycleRuntime runtime = new CycleRuntime(
                simulationRunId,
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

    public SimulationCommandCycleStatusResponse configure(
            Long simulationRunId,
            FulfillmentCommandGenerateRequest request
    ) {
        SimulationRun run = findRun(simulationRunId);
        CycleRuntime runtime = runtimes.computeIfAbsent(
                simulationRunId,
                ignored -> new CycleRuntime(
                        simulationRunId,
                        intervalMs(run),
                        FulfillmentCommandGenerateRequest.automatic(),
                        run.getStatus() == SimulationRunStatus.RUNNING
                )
        );
        runtime.configure(request);
        return runtime.snapshot();
    }

    public SimulationCommandCycleStatusResponse status(Long simulationRunId) {
        findRun(simulationRunId);
        CycleRuntime runtime = runtimes.get(simulationRunId);
        if (runtime == null) {
            return new SimulationCommandCycleStatusResponse(
                    simulationRunId,
                    false,
                    CycleState.IDLE,
                    0,
                    0,
                    0,
                    null,
                    FulfillmentCommandGenerateRequest.automatic().effectiveCommandExpressionMode(),
                    FulfillmentCommandGenerateRequest.automatic().effectivePolicyProfile(),
                    null,
                    null,
                    null,
                    LocalDateTime.now()
            );
        }
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

            // 이 실행·이 주기의 계획이 이미 저장돼 있으면 AI 를 거치지 않는다.
            // 초기화 후 다시 시작한 경우가 여기에 해당하며,
            // 처음 실행과 똑같은 작업·똑같은 경로가 다시 돈다.
            SimulationRunPlanSnapshot snapshot = planSnapshotStore
                    .find(simulationRunId, cycleMinute);

            if (snapshot != null) {
                runtime.begin(CycleState.PLANNING, "REPLAY");

                LaroPlanResponse replayed = laroPlanService.replay(
                        simulationRunId,
                        planSnapshotStore.readRequest(snapshot),
                        planSnapshotStore.readResponse(snapshot)
                );

                runtime.complete(replayed);
                log.info(
                        "[command-cycle] runId={}, minute={} 저장된 계획을 그대로 재생했습니다. (AI 호출 없음)",
                        simulationRunId,
                        cycleMinute
                );
                return;
            }

            runtime.begin(CycleState.GENERATING, null);
            FulfillmentCommandGenerateResponse generated =
                    commandGenerationService.generate(
                            simulationRunId,
                            runtime.activeGenerationRequest()
                    );
            runtime.generated(generated);
            LaroPlanRequest planRequest = generated.planRequest();

            runtime.requireActive();
            boolean replan = playbackService.hasActiveAiPlan(simulationRunId);
            String planningMode = replan ? "REPLAN" : "INITIAL_PLAN";
            runtime.begin(replan ? CycleState.REPLANNING : CycleState.PLANNING, planningMode);

            LaroPlanResponse response = replan
                    ? laroPlanService.replan(simulationRunId, planRequest)
                    : laroPlanService.plan(simulationRunId, planRequest);
            runtime.complete(response);

            // 다음 초기화 때 그대로 다시 쓸 수 있게 남겨 둔다.
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
        private final long intervalMs;
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
        private FulfillmentCommandGenerateResponse generated;
        private LaroPlanResponse planResponse;
        private String error;
        private LocalDateTime updatedAt = LocalDateTime.now();

        private CycleRuntime(
                Long simulationRunId,
                long intervalMs,
                FulfillmentCommandGenerateRequest generationRequest,
                boolean active
        ) {
            this.simulationRunId = simulationRunId;
            this.intervalMs = intervalMs;
            this.generationRequest = generationRequest;
            this.activeGenerationRequest = generationRequest;
            this.active = active;
        }

        synchronized Long simulationRunId() {
            return simulationRunId;
        }

        synchronized FulfillmentCommandGenerateRequest generationRequest() {
            return generationRequest;
        }

        synchronized FulfillmentCommandGenerateRequest activeGenerationRequest() {
            return activeGenerationRequest;
        }

        synchronized void configure(FulfillmentCommandGenerateRequest request) {
            generationRequest = request == null
                    ? FulfillmentCommandGenerateRequest.automatic()
                    : request;
            updatedAt = LocalDateTime.now();
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
            return accept(minute);
        }

        synchronized Long acceptManualCycle() {
            if (!active || inFlight) {
                return null;
            }
            return accept(simulatedTimeMs / intervalMs);
        }

        private Long accept(long minute) {
            inFlight = true;
            activeCycleMinute = minute;
            state = CycleState.CHECKING;
            planningMode = null;
            generated = null;
            planResponse = null;
            error = null;
            activeGenerationRequest = generationRequest;
            updatedAt = LocalDateTime.now();
            return minute;
        }

        synchronized void begin(CycleState nextState, String nextPlanningMode) {
            requireActive();
            state = nextState;
            if (nextPlanningMode != null) {
                planningMode = nextPlanningMode;
            }
            updatedAt = LocalDateTime.now();
        }

        synchronized void generated(FulfillmentCommandGenerateResponse value) {
            requireActive();
            generated = value;
            updatedAt = LocalDateTime.now();
        }

        synchronized void complete(LaroPlanResponse value) {
            if (!active) {
                return;
            }
            planResponse = value;
            state = CycleState.COMPLETE;
            error = null;
            inFlight = false;
            updatedAt = LocalDateTime.now();
        }

        synchronized void fail(String message) {
            if (!active) {
                return;
            }
            state = CycleState.ERROR;
            error = message == null || message.isBlank() ? "command cycle failed" : message;
            inFlight = false;
            updatedAt = LocalDateTime.now();
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
            updatedAt = LocalDateTime.now();
            lastTickNanos = System.nanoTime();
        }

        synchronized SimulationCommandCycleStatusResponse snapshot() {
            long nextMinute = Math.max(lastTriggeredMinute + 1, simulatedTimeMs / intervalMs + 1);
            return new SimulationCommandCycleStatusResponse(
                    simulationRunId,
                    active,
                    state,
                    simulatedTimeMs,
                    activeCycleMinute,
                    nextMinute * intervalMs,
                    planningMode,
                    generationRequest.effectiveCommandExpressionMode(),
                    generationRequest.effectivePolicyProfile(),
                    generated,
                    planResponse,
                    error,
                    updatedAt
            );
        }
    }
}
