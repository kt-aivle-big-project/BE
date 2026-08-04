package com.aivle.be.task.service;

import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskCreationServiceRackLevelTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
    private final WarehouseNodeRepository nodeRepository = mock(WarehouseNodeRepository.class);
    private final WarehouseItemRepository itemRepository = mock(WarehouseItemRepository.class);
    private final SimulationRunRepository runRepository = mock(SimulationRunRepository.class);
    private final TaskCreationService service = new TaskCreationService(
            taskRepository,
            warehouseRepository,
            nodeRepository,
            itemRepository,
            runRepository
    );

    @BeforeEach
    void setUp() {
        Warehouse warehouse = mock(Warehouse.class);
        WarehouseNode start = mock(WarehouseNode.class);
        WarehouseNode end = mock(WarehouseNode.class);
        when(warehouse.getId()).thenReturn(1L);
        when(start.getWarehouse()).thenReturn(warehouse);
        when(end.getWarehouse()).thenReturn(warehouse);
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(nodeRepository.findByIdAndActiveTrue(10L)).thenReturn(Optional.of(start));
        when(nodeRepository.findByIdAndActiveTrue(20L)).thenReturn(Optional.of(end));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void outboundIgnoresAccidentalSourceRackLevelInTargetField() {
        Task task = service.create(command(TaskType.OUTBOUND, 3));

        assertThat(task.getTargetRackLevel()).isNull();
    }

    @Test
    void inboundPreservesTargetRackLevel() {
        Task task = service.create(command(TaskType.INBOUND, 3));

        assertThat(task.getTargetRackLevel()).isEqualTo(3);
    }

    private TaskCreateCommand command(TaskType type, Integer targetRackLevel) {
        return new TaskCreateCommand(
                1L,
                10L,
                20L,
                null,
                1L,
                type,
                null,
                20,
                0,
                type == TaskType.INBOUND ? "IN-001" : "ORD-001",
                targetRackLevel
        );
    }
}
