package com.aivle.be.task.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousezone.service.WarehouseZoneResolverService;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TaskInventoryService {

    private final WarehouseItemRepository warehouseItemRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseZoneResolverService warehouseZoneResolverService;

    @Transactional
    public void applyCompletion(Task task) {
        if (task.getTaskType() == TaskType.INBOUND) {
            applyInbound(task);
            return;
        }
        if (task.getTaskType() == TaskType.OUTBOUND) {
            applyOutbound(task);
        }
    }

    private void applyInbound(Task task) {
        Long itemId = requireItemId(task);
        int quantity = task.effectiveQuantity();
        WarehouseNode targetNode = task.getEndNode();
        warehouseZoneResolverService.requireStorageZone(targetNode);

        StorageLocation targetLocation = storageLocationRepository
                .findByWarehouse_IdAndNode_Id(task.getWarehouse().getId(), targetNode.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_LOCATION_NOT_FOUND));

        WarehouseItem targetItem = warehouseItemRepository
                .findFirstByWarehouse_IdAndItemIdAndNode_Id(
                        task.getWarehouse().getId(),
                        itemId,
                        targetNode.getId()
                )
                .orElseGet(() -> warehouseItemRepository.save(WarehouseItem.create(
                        task.getWarehouse(),
                        targetLocation,
                        targetNode,
                        itemId,
                        null,
                        LocalDateTime.now(),
                        0
                )));

        targetItem.increaseQuantity(quantity);
    }

    private void applyOutbound(Task task) {
        int quantity = task.effectiveQuantity();
        WarehouseNode sourceNode = task.getStartNode();
        warehouseZoneResolverService.requireStorageZone(sourceNode);

        WarehouseItem sourceItem = task.getWarehouseItem();
        if (sourceItem == null) {
            Long itemId = requireItemId(task);
            sourceItem = warehouseItemRepository
                    .findFirstByWarehouse_IdAndItemIdAndNode_Id(
                            task.getWarehouse().getId(),
                            itemId,
                            sourceNode.getId()
                    )
                    .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_ITEM_NOT_FOUND));
        }

        if (!sourceItem.getNode().getId().equals(sourceNode.getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        sourceItem.decreaseQuantity(quantity);
    }

    private Long requireItemId(Task task) {
        Long itemId = task.getEffectiveItemId();
        if (itemId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return itemId;
    }
}
