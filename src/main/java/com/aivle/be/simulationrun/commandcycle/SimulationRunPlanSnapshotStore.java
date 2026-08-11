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
 * 성공한 AI 계획을 실행·주기 단위의 진단 이력으로 저장한다.
 *
 * <p>초기화 시 이력은 삭제하며 새 실행 세대는 새 AI 계획을 만든다.
 * 이력 저장 실패가 시뮬레이션 실행을 중단시키지는 않는다.</p>
 */
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

    /**
     * 초기화된 실행은 이전 실행 계획을 재생하지 않고 새 계획을 만든다.
     * 예약이 해제된 과거 계획을 다시 설치하면 계획과 재고 소유권이 어긋난다.
     */
    @Transactional
    public void deleteAll(Long simulationRunId) {
        repository.deleteAllBySimulationRunId(simulationRunId);
    }
}
