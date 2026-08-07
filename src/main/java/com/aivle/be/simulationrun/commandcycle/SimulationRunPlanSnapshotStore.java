package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.simulationrun.entity.SimulationRunPlanSnapshot;
import com.aivle.be.simulationrun.repository.SimulationRunPlanSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 계획을 실행·주기 단위로 저장하고 다시 꺼내 준다.
 *
 * <p>초기화 후 재시작하면 저장된 계획을 그대로 재생하므로 AI 를 다시 부르지 않는다.
 * 저장에 실패해도 시뮬레이션은 계속 돌아야 하므로 예외를 밖으로 던지지 않는다.
 * (그 경우 다음 초기화 때 재생 대신 새 계획을 만들 뿐이다)
 */
@Service
@RequiredArgsConstructor
public class SimulationRunPlanSnapshotStore {

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunPlanSnapshotStore.class);

    private final SimulationRunPlanSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public SimulationRunPlanSnapshot find(Long simulationRunId, long cycleMinute) {
        return repository
                .findBySimulationRunIdAndCycleMinute(simulationRunId, cycleMinute)
                .orElse(null);
    }

    public LaroPlanRequest readRequest(SimulationRunPlanSnapshot snapshot) {
        return objectMapper.readValue(snapshot.getRequestJson(), LaroPlanRequest.class);
    }

    public LaroPlanResponse readResponse(SimulationRunPlanSnapshot snapshot) {
        return objectMapper.readValue(snapshot.getResponseJson(), LaroPlanResponse.class);
    }

    @Transactional
    public void save(
            Long simulationRunId,
            long cycleMinute,
            LaroPlanRequest request,
            LaroPlanResponse response
    ) {
        if (request == null || response == null) {
            return;
        }
        if (repository.findBySimulationRunIdAndCycleMinute(simulationRunId, cycleMinute)
                .isPresent()) {
            return;
        }
        try {
            repository.save(SimulationRunPlanSnapshot.create(
                    simulationRunId,
                    cycleMinute,
                    objectMapper.writeValueAsString(request),
                    objectMapper.writeValueAsString(response)
            ));
        } catch (RuntimeException exception) {
            log.warn(
                    "[command-cycle] runId={}, minute={} 계획 스냅샷 저장 실패: {}",
                    simulationRunId,
                    cycleMinute,
                    exception.getMessage()
            );
        }
    }

    /**
     * 이 실행에 재생할 계획이 하나라도 저장돼 있는지.
     * 새 실행은 없고, 초기화 후 재시작이면 있다.
     */
    @Transactional(readOnly = true)
    public boolean hasSnapshot(Long simulationRunId) {
        return repository.existsBySimulationRunId(simulationRunId);
    }
}
