package com.aivle.be.simulationrun.commandcycle;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationReplanPlanRequestFactoryTest {

    @Test
    void enrichesInboundWithCommittedTaskRackAndLevel() {
        TaskRepository repository = mock(TaskRepository.class);
        Task task = task(
                41L,
                "IN-001",
                TaskType.INBOUND,
                node(10L, "IN_HANDOFF_1"),
                node(220L, "K2_2"),
                3
        );
        when(repository.findAllBySimulationRun_IdOrderByRequestedAtAsc(7L))
                .thenReturn(List.of(task));

        LaroPlanRequest request = request(operation(
                "IN-001",
                LaroPlanRequest.OperationType.INBOUND,
                null,
                null,
                null,
                null
        ));

        LaroPlanRequest result = new SimulationReplanPlanRequestFactory(repository)
                .enrichWithCurrentTaskContracts(7L, request);

        LaroPlanRequest.StructuredOperation operation = result
                .structuredInput().operations().get(0);
        assertThat(operation.taskId()).isEqualTo(41L);
        assertThat(operation.destinationNodeId()).isEqualTo(220L);
        assertThat(operation.destinationNodeCode()).isEqualTo("K2_2");
        assertThat(operation.targetRackLevel()).isEqualTo(3);
        assertThat(operation.sourceNodeCode()).isEqualTo("INBOUND-ORIGINAL");
        assertThat(result.structuredInput().constraints()).containsEntry("policy", "BALANCED");
        assertThat(result.optimizationBackend()).isEqualTo("cuopt");
    }

    @Test
    void bindsOutboundTaskAndCurrentSourceWithoutChangingBusinessFields() {
        TaskRepository repository = mock(TaskRepository.class);
        Task task = task(
                51L,
                "OUT-001",
                TaskType.OUTBOUND,
                node(330L, "K3_3"),
                node(90L, "OUT_STATION_1"),
                null
        );
        when(repository.findAllBySimulationRun_IdOrderByRequestedAtAsc(7L))
                .thenReturn(List.of(task));
        LaroPlanRequest.StructuredOperation original = operation(
                "OUT-001",
                LaroPlanRequest.OperationType.OUTBOUND,
                null,
                77L,
                null,
                null
        );

        LaroPlanRequest.StructuredOperation result =
                new SimulationReplanPlanRequestFactory(repository)
                        .enrichWithCurrentTaskContracts(7L, request(original))
                        .structuredInput().operations().get(0);

        assertThat(result.taskId()).isEqualTo(51L);
        assertThat(result.sourceNodeId()).isEqualTo(330L);
        assertThat(result.sourceNodeCode()).isEqualTo("K3_3");
        assertThat(result.sourceWarehouseItemId()).isEqualTo(77L);
        assertThat(result.destinationFacilityCode()).isEqualTo("OUT_STATION_1");
        assertThat(result.quantity()).isEqualTo(2);
    }

    @Test
    void leavesUnmatchedOperationUntouched() {
        TaskRepository repository = mock(TaskRepository.class);
        when(repository.findAllBySimulationRun_IdOrderByRequestedAtAsc(7L))
                .thenReturn(List.of());
        LaroPlanRequest.StructuredOperation original = operation(
                "IN-NOT-YET-CREATED",
                LaroPlanRequest.OperationType.INBOUND,
                null,
                null,
                "K1_1",
                2
        );

        LaroPlanRequest.StructuredOperation result =
                new SimulationReplanPlanRequestFactory(repository)
                        .enrichWithCurrentTaskContracts(7L, request(original))
                        .structuredInput().operations().get(0);

        assertThat(result).isSameAs(original);
    }

    private static Task task(
            Long id,
            String operationId,
            TaskType taskType,
            WarehouseNode start,
            WarehouseNode end,
            Integer rackLevel
    ) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getExternalOperationId()).thenReturn(operationId);
        when(task.getTaskType()).thenReturn(taskType);
        when(task.getStartNode()).thenReturn(start);
        when(task.getEndNode()).thenReturn(end);
        when(task.getTargetRackLevel()).thenReturn(rackLevel);
        return task;
    }

    private static WarehouseNode node(Long id, String code) {
        WarehouseNode node = mock(WarehouseNode.class);
        when(node.getId()).thenReturn(id);
        when(node.getNodeCode()).thenReturn(code);
        return node;
    }

    private static LaroPlanRequest request(
            LaroPlanRequest.StructuredOperation operation
    ) {
        return new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REQ-LOW-BATTERY-7",
                        List.of(operation),
                        Map.of("policy", "BALANCED"),
                        new LaroPlanRequest.RoutingContext(1, 3, 5, 6, 1, 4, "runtime")
                ),
                null,
                "cuopt",
                null
        );
    }

    private static LaroPlanRequest.StructuredOperation operation(
            String operationId,
            LaroPlanRequest.OperationType type,
            Long taskId,
            Long sourceWarehouseItemId,
            String destinationNodeCode,
            Integer targetRackLevel
    ) {
        return new LaroPlanRequest.StructuredOperation(
                operationId,
                type,
                taskId,
                11L,
                "ITEM-011",
                2,
                "HIGH",
                sourceWarehouseItemId,
                null,
                null,
                "INBOUND-ORIGINAL",
                null,
                null,
                null,
                destinationNodeCode,
                type == LaroPlanRequest.OperationType.OUTBOUND
                        ? "OUT_STATION_1"
                        : null,
                targetRackLevel,
                0L,
                2_000L,
                5_000L,
                "{}"
        );
    }
}
