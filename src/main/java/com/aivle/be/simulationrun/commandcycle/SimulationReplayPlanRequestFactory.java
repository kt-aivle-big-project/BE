package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskStatus;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 초기화 후 "같은 시뮬레이션을 처음부터 다시" 돌리기 위한 계획 요청을 만든다.
 *
 * <p>일반 실행은 {@code FulfillmentCommandGenerationService}가 매번 새 BOX 배치를
 * 무작위로 뽑는다. 그래서 초기화하고 다시 시작하면 실행 ID만 같고 작업 목록은
 * 완전히 달라졌다.
 *
 * <p>재생은 그러면 안 된다. 이 클래스는 새로 뽑는 대신 <b>이미 저장된 작업</b>을
 * 그대로 구조화 업무로 옮긴다. {@code taskId}를 채워 보내므로 LARO 응답을 반영할 때
 * {@code LaroPlanExecutionService}가 새 Task를 만들지 않고 기존 Task를 다시 쓴다.
 */
@Service
@RequiredArgsConstructor
public class SimulationReplayPlanRequestFactory {

    private final TaskRepository taskRepository;

    /**
     * 이 실행에 재생할 작업이 남아 있으면 계획 요청을 만든다.
     *
     * @return 재생할 작업이 없으면 null
     */
    @Transactional(readOnly = true)
    public LaroPlanRequest build(Long simulationRunId) {
        List<LaroPlanRequest.StructuredOperation> operations = taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId)
                .stream()
                .filter(SimulationReplayPlanRequestFactory::isReplayable)
                .map(SimulationReplayPlanRequestFactory::toOperation)
                .toList();

        if (operations.isEmpty()) {
            return null;
        }

        return new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REPLAY-RUN-" + simulationRunId,
                        operations,
                        null,
                        null
                ),
                null,
                null,
                null
        );
    }

    /** 이 실행에 재생할 수 있는 작업이 있는지. */
    @Transactional(readOnly = true)
    public boolean hasReplayableTasks(Long simulationRunId) {
        return taskRepository
                .findAllBySimulationRun_IdOrderByRequestedAtAsc(simulationRunId)
                .stream()
                .anyMatch(SimulationReplayPlanRequestFactory::isReplayable);
    }

    /**
     * 재생 대상은 아직 시작하지 않은 입·출고 작업이다.
     *
     * <p>초기화가 모든 작업을 PENDING 으로 되돌리므로, 초기화 직후에는
     * 이전 실행의 작업 전부가 여기에 해당한다.
     */
    private static boolean isReplayable(Task task) {
        return task.getStatus() == TaskStatus.PENDING
                && (task.getTaskType() == TaskType.INBOUND
                        || task.getTaskType() == TaskType.OUTBOUND)
                && task.getStartNode() != null
                && task.getEndNode() != null;
    }

    private static LaroPlanRequest.StructuredOperation toOperation(Task task) {
        return new LaroPlanRequest.StructuredOperation(
                // 원래 붙어 있던 AI operation_id 를 유지한다.
                // 없으면 작업 ID 로 안정적인 값을 만든다.
                task.getExternalOperationId() == null
                        ? "REPLAY-TASK-" + task.getId()
                        : task.getExternalOperationId(),
                task.getTaskType() == TaskType.INBOUND
                        ? LaroPlanRequest.OperationType.INBOUND
                        : LaroPlanRequest.OperationType.OUTBOUND,
                // taskId 를 채우면 기존 작업을 그대로 쓴다 (새로 만들지 않는다)
                task.getId(),
                task.getEffectiveItemId(),
                null,
                task.effectiveQuantity(),
                null,
                task.getWarehouseItem() == null
                        ? null
                        : task.getWarehouseItem().getId(),
                null,
                task.getStartNode().getId(),
                task.getStartNode().getNodeCode(),
                null,
                null,
                task.getEndNode().getId(),
                task.getEndNode().getNodeCode(),
                null,
                task.getTargetRackLevel(),
                task.effectiveReleaseAtSeconds() * 1000L,
                null,
                null,
                null
        );
    }
}
