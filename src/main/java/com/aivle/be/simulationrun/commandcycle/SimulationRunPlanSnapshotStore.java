package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.simulationrun.controller.response.SimulationRunPlanSnapshotResponse;
import com.aivle.be.simulationrun.entity.SimulationRunPlanSnapshot;
import com.aivle.be.simulationrun.repository.SimulationRunPlanSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

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

    public List<SimulationRunPlanSnapshotResponse> findAll(Long simulationRunId) {
        return repository
                .findAllBySimulationRunIdOrderByCycleMinuteAsc(simulationRunId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Optional<SimulationRunPlanSnapshotResponse> findLatest(Long simulationRunId) {
        return repository
                .findFirstBySimulationRunIdOrderByCycleMinuteDesc(simulationRunId)
                .map(this::toResponse);
    }

    private SimulationRunPlanSnapshotResponse toResponse(SimulationRunPlanSnapshot snapshot) {
        return new SimulationRunPlanSnapshotResponse(
                snapshot.getId(),
                snapshot.getSimulationRunId(),
                snapshot.getCycleMinute(),
                read(snapshot.getRequestJson(), LaroPlanRequest.class),
                read(snapshot.getResponseJson(), LaroPlanResponse.class),
                snapshot.getCreatedAt()
        );
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (RuntimeException exception) {
            log.warn(
                    "[command-cycle] 계획 스냅샷 역직렬화 실패 type={}: {}",
                    type.getSimpleName(),
                    exception.getMessage()
            );
            return null;
        }
    }

    @Transactional
    public void deleteAll(Long simulationRunId) {
        repository.deleteAllBySimulationRunId(simulationRunId);
    }
}
