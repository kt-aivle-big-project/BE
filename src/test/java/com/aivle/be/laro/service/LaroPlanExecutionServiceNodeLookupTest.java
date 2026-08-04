package com.aivle.be.laro.service;

import com.aivle.be.laro.dto.LaroPlanResponse;
import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.task.service.TaskCreationService;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LaroPlanExecutionServiceNodeLookupTest {

    private final WarehouseNodeRepository warehouseNodeRepository =
            mock(WarehouseNodeRepository.class);
    private final LaroPlanExecutionService service = new LaroPlanExecutionService(
            mock(SimulationRunRepository.class),
            mock(TaskRepository.class),
            mock(TaskCreationService.class),
            warehouseNodeRepository,
            mock(SimulationPlaybackService.class),
            mock(JdbcTemplate.class)
    );

    @Test
    void nodeCodeLookupIgnoresRetiredLogicalDestination() {
        when(warehouseNodeRepository
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(1L, "O_A"))
                .thenReturn(Optional.empty());

        WarehouseNode resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveNode",
                1L,
                null,
                "O_A"
        );

        assertThat(resolved).isNull();
        verify(warehouseNodeRepository)
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(1L, "O_A");
        verify(warehouseNodeRepository, never())
                .findByWarehouse_IdAndNodeCode(1L, "O_A");
    }

    @Test
    void nodeCodeLookupReturnsActivePhysicalServiceNode() {
        WarehouseNode stationNode = mock(WarehouseNode.class);
        when(warehouseNodeRepository
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(1L, "R3_10"))
                .thenReturn(Optional.of(stationNode));

        WarehouseNode resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveNode",
                1L,
                null,
                "R3_10"
        );

        assertThat(resolved).isSameAs(stationNode);
    }

    @Test
    void rackSelectionResolvesThePhysicalRackStorageNode() {
        WarehouseNode rackNode = mock(WarehouseNode.class);
        when(rackNode.getNodeType()).thenReturn(NodeType.RACK_STORAGE);
        when(warehouseNodeRepository
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(1L, "K4_1"))
                .thenReturn(Optional.of(rackNode));
        LaroPlanResponse.LogicalOperation operation = logicalOperation("K4_1", 3);

        WarehouseNode resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveRackNode",
                1L,
                operation
        );

        assertThat(resolved).isSameAs(rackNode);
    }

    @Test
    void routeNodeIsNotAcceptedAsTheTaskRackDestination() {
        WarehouseNode routeNode = mock(WarehouseNode.class);
        when(routeNode.getNodeType()).thenReturn(NodeType.ROUTE);
        when(warehouseNodeRepository
                .findByWarehouse_IdAndNodeCodeAndActiveTrue(1L, "R5_1"))
                .thenReturn(Optional.of(routeNode));
        LaroPlanResponse.LogicalOperation operation = logicalOperation("R5_1", 3);

        WarehouseNode resolved = ReflectionTestUtils.invokeMethod(
                service,
                "resolveRackNode",
                1L,
                operation
        );

        assertThat(resolved).isNull();
    }

    @Test
    void outboundSourceRackLevelIsNotMappedToTargetRackLevel() {
        LaroPlanRequest.StructuredOperation request = operation(
                LaroPlanRequest.OperationType.OUTBOUND,
                null
        );

        Integer resolved = ReflectionTestUtils.invokeMethod(
                service,
                "plannedRackLevel",
                request,
                logicalOperation("K4_1", 3)
        );

        assertThat(resolved).isNull();
    }

    @Test
    void inboundPlannedRackLevelIsPreserved() {
        LaroPlanRequest.StructuredOperation request = operation(
                LaroPlanRequest.OperationType.INBOUND,
                null
        );

        Integer resolved = ReflectionTestUtils.invokeMethod(
                service,
                "plannedRackLevel",
                request,
                logicalOperation("K4_1", 3)
        );

        assertThat(resolved).isEqualTo(3);
    }

    private LaroPlanRequest.StructuredOperation operation(
            LaroPlanRequest.OperationType type,
            Integer targetRackLevel
    ) {
        return new LaroPlanRequest.StructuredOperation(
                type == LaroPlanRequest.OperationType.INBOUND ? "IN-001" : "ORD-001",
                type,
                null,
                1L,
                "ITEM-001",
                20,
                "medium",
                type == LaroPlanRequest.OperationType.OUTBOUND ? 10001L : null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                targetRackLevel,
                0L,
                1200L,
                1200L,
                null
        );
    }

    private LaroPlanResponse.LogicalOperation logicalOperation(
            String rackId,
            Integer rackLevel
    ) {
        return new LaroPlanResponse.LogicalOperation(
                "IN-001",
                "INBOUND_ITEM",
                "ITEM-001",
                20,
                rackId,
                rackLevel,
                rackId,
                "IN_HANDOFF_1",
                "HU-IN-001",
                "R10001",
                java.util.List.of("TASK-001")
        );
    }
}
