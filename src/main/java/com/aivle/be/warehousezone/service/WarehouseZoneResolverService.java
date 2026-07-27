package com.aivle.be.warehousezone.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseZoneResolverService {

    private final WarehouseZoneRepository warehouseZoneRepository;

    public WarehouseZone resolve(WarehouseNode node) {
        return warehouseZoneRepository.findByWarehouse_IdAndName(
                        node.getWarehouse().getId(),
                        node.getZoneId()
                )
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT));
    }

    public void requireStorageZone(WarehouseNode node) {
        WarehouseZone zone = resolve(node);
        if (!zone.isStorageZone()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    public void requireStorageZone(WarehouseZone zone) {
        if (!zone.isStorageZone()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }
}
