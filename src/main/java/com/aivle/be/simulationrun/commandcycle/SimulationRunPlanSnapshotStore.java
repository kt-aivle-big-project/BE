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

@Service
@RequiredArgsConstructor
public class SimulationRunPlanSnapshotStore {

    private static final Logger log =
            LoggerFactory.getLogger(SimulationRunPlanSnapshotStore.class);

    private final SimulationRunPlanSnapshotRepository repository;
    private final ObjectMapper objectMapper;

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

    @Transactional
    public void deleteAll(Long simulationRunId) {
        repository.deleteAllBySimulationRunId(simulationRunId);
    }
}
