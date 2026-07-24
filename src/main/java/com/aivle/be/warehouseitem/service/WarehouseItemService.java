package com.aivle.be.warehouseitem.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseitem.dto.WarehouseItemRequest;
import com.aivle.be.warehouseitem.dto.WarehouseItemResponse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousezone.service.WarehouseZoneResolverService;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseItemService {

    private final WarehouseItemRepository warehouseItemRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final WarehouseZoneResolverService warehouseZoneResolverService;

    @Transactional
    public WarehouseItemResponse create(WarehouseItemRequest request) {
        Warehouse warehouse = findWarehouse(request.warehouseId());
        StorageLocation storageLocation = findStorageLocation(request.storageLocationId());
        requireSameWarehouse(warehouse, storageLocation);

        WarehouseNode node = storageLocation.getNode();
        warehouseZoneResolverService.requireStorageZone(node);

        WarehouseItem item = WarehouseItem.create(
                warehouse,
                storageLocation,
                node,
                request.itemId(),
                request.expiryDate(),
                LocalDateTime.now(),
                request.quantity()
        );

        return WarehouseItemResponse.from(warehouseItemRepository.save(item));
    }

    public WarehouseItemResponse get(Long warehouseItemId) {
        return WarehouseItemResponse.from(findById(warehouseItemId));
    }

    public List<WarehouseItemResponse> getAll() {
        return warehouseItemRepository.findAll()
                .stream()
                .map(WarehouseItemResponse::from)
                .toList();
    }

    public List<WarehouseItemResponse> getByWarehouse(Long warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
        }
        return warehouseItemRepository.findAllByWarehouse_Id(warehouseId)
                .stream()
                .map(WarehouseItemResponse::from)
                .toList();
    }

    @Transactional
    public WarehouseItemResponse update(Long warehouseItemId, WarehouseItemRequest request) {
        WarehouseItem item = findById(warehouseItemId);
        Warehouse warehouse = findWarehouse(request.warehouseId());
        StorageLocation storageLocation = findStorageLocation(request.storageLocationId());
        requireSameWarehouse(warehouse, storageLocation);

        warehouseZoneResolverService.requireStorageZone(storageLocation.getNode());

        item.update(
                storageLocation,
                storageLocation.getNode(),
                request.itemId(),
                request.expiryDate(),
                request.quantity()
        );

        return WarehouseItemResponse.from(item);
    }

    @Transactional
    public void delete(Long warehouseItemId) {
        WarehouseItem item = findById(warehouseItemId);
        warehouseItemRepository.delete(item);
    }

    private WarehouseItem findById(Long warehouseItemId) {
        return warehouseItemRepository.findById(warehouseItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_ITEM_NOT_FOUND));
    }

    private Warehouse findWarehouse(Long warehouseId) {
        return warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
    }

    private StorageLocation findStorageLocation(Long storageLocationId) {
        return storageLocationRepository.findById(storageLocationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_LOCATION_NOT_FOUND));
    }

    private void requireSameWarehouse(Warehouse warehouse, StorageLocation storageLocation) {
        if (!storageLocation.getWarehouse().getId().equals(warehouse.getId())) {
            throw new BusinessException(ErrorCode.WAREHOUSE_ITEM_LOCATION_MISMATCH);
        }
    }
}
