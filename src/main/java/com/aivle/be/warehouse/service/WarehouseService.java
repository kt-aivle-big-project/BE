package com.aivle.be.warehouse.service;

import java.util.List;
import com.aivle.be.warehouse.dto.WarehouseUpdateRequest;
import com.aivle.be.warehouse.domain.Warehouse;
import com.aivle.be.warehouse.dto.WarehouseCreateRequest;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseResponse createWarehouse(WarehouseCreateRequest request) {
        Warehouse warehouse = Warehouse.create(
                request.getName(),
                request.getWidth(),
                request.getHeight(),
                request.getUserId()
        );
        Warehouse savedWarehouse = warehouseRepository.save(warehouse);

        return WarehouseResponse.from(savedWarehouse);
    }
    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("창고를 찾을 수 없습니다."));

        return WarehouseResponse.from(warehouse);
    }
    @Transactional(readOnly = true)
    public List<WarehouseResponse> getWarehouses() {
        return warehouseRepository.findAll()
                .stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    public WarehouseResponse updateWarehouse(
            Long warehouseId,
            WarehouseUpdateRequest request
    ) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("창고를 찾을 수 없습니다."));

        warehouse.update(
                request.getName(),
                request.getWidth(),
                request.getHeight()
        );

        return WarehouseResponse.from(warehouse);
    }

    public void deleteWarehouse(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException("창고를 찾을 수 없습니다."));

        warehouseRepository.delete(warehouse);
    }
}