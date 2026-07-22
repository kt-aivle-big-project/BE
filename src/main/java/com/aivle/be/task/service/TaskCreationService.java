package com.aivle.be.task.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskCreationService {

    private final TaskRepository taskRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final SimulationRunRepository simulationRunRepository;

    @Transactional
    public Task create(TaskCreateCommand command) {
        Warehouse warehouse = warehouseRepository.findById(command.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        WarehouseNode startNode = findNode(command.startNodeId());
        WarehouseNode endNode = findNode(command.endNodeId());
        requireSameWarehouse(warehouse, startNode);
        requireSameWarehouse(warehouse, endNode);

        WarehouseItem warehouseItem = command.warehouseItemId() == null
                ? null
                : warehouseItemRepository.findById(command.warehouseItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
        if (warehouseItem != null
                && !warehouseItem.getWarehouse().getId().equals(warehouse.getId())) {
            throw new BusinessException(ErrorCode.TASK_SIMULATION_RUN_MISMATCH);
        }

        SimulationRun simulationRun = command.simulationRunId() == null
                ? null
                : simulationRunRepository.findById(command.simulationRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SIMULATION_RUN_NOT_FOUND));
        if (simulationRun != null
                && !simulationRun.getWarehouse().getId().equals(warehouse.getId())) {
            throw new BusinessException(ErrorCode.TASK_SIMULATION_RUN_MISMATCH);
        }

        return taskRepository.save(new Task(
                warehouse,
                startNode,
                endNode,
                command.taskType(),
                warehouseItem,
                simulationRun
        ));
    }

    private WarehouseNode findNode(Long nodeId) {
        return warehouseNodeRepository.findById(nodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NODE_NOT_FOUND));
    }

    private void requireSameWarehouse(Warehouse warehouse, WarehouseNode node) {
        if (!node.getWarehouse().getId().equals(warehouse.getId())) {
            throw new BusinessException(ErrorCode.TASK_SIMULATION_RUN_MISMATCH);
        }
    }
}
