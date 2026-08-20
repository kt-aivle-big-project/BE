package com.aivle.be.task.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
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
    private final ProductRepository productRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseZoneResolverService warehouseZoneResolverService;

    @Transactional
    public boolean applyForServiceCompletion(Task task, String serviceKind) {
        if (task == null || task.isInventoryApplied() || serviceKind == null) {
            return false;
        }
        String normalizedKind = serviceKind.trim().toUpperCase();
        boolean rackServiceCompleted =
                (task.getTaskType() == TaskType.OUTBOUND && "PICKUP".equals(normalizedKind))
                || (task.getTaskType() == TaskType.INBOUND && "DROP".equals(normalizedKind));
        if (!rackServiceCompleted) {
            return false;
        }
        applyAndMark(task);
        return true;
    }

    public boolean applyCompletion(Task task) {
        if (task == null || task.isInventoryApplied()) {
            return false;
        }
        applyAndMark(task);
        return true;
    }

    private void applyAndMark(Task task) {
        if (task.getTaskType() == TaskType.INBOUND) {
            applyInbound(task);
        } else if (task.getTaskType() == TaskType.OUTBOUND) {
            applyOutbound(task);
        } else {
            return;
        }
        task.markInventoryApplied();
    }

    private void applyInbound(Task task) {
        Long itemId = requireItemId(task);
        Product product = productRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        int quantity = task.effectiveQuantity();
        WarehouseNode targetNode = task.getEndNode();
        warehouseZoneResolverService.requireStorageZone(targetNode);

        StorageLocation targetLocation = storageLocationRepository
                .findByWarehouse_IdAndNode_Id(task.getWarehouse().getId(), targetNode.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_LOCATION_NOT_FOUND));

        int rackLevel = resolveInboundRackLevel(task, targetLocation.getId());
        LocalDateTime receivedAt = LocalDateTime.now();
        WarehouseItem targetItem = warehouseItemRepository
                .findByStorageLocation_IdAndRackLevel(targetLocation.getId(), rackLevel)
                .map(existing -> {
                    if (existing.getQuantity() != null && existing.getQuantity() > 0) {
                        throw new BusinessException(ErrorCode.INVALID_INPUT);
                    }
                    existing.replaceEmptyBox(product, receivedAt);
                    return existing;
                })
                .orElseGet(() -> warehouseItemRepository.save(WarehouseItem.create(
                        task.getWarehouse(),
                        targetLocation,
                        rackLevel,
                        targetNode,
                        product,
                        receivedAt,
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
                    .findFirstByWarehouse_IdAndProduct_IdAndNode_Id(
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

    private int firstEmptyRackLevel(Long storageLocationId) {
        for (int rackLevel = 1; rackLevel <= 3; rackLevel++) {
            if (!warehouseItemRepository.existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(
                    storageLocationId, rackLevel, 0)) {
                return rackLevel;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT);
    }

    private int resolveInboundRackLevel(Task task, Long storageLocationId) {
        Integer plannedRackLevel = task.getTargetRackLevel();
        if (plannedRackLevel == null) {
            return firstEmptyRackLevel(storageLocationId);
        }
        if (warehouseItemRepository.existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(
                storageLocationId, plannedRackLevel, 0)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return plannedRackLevel;
    }
}
