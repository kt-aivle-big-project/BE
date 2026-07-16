package com.aivle.be.warehousezone.service;

import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousezone.dto.WarehouseZoneCreateRequest;
import com.aivle.be.warehousezone.dto.WarehouseZoneResponse;
import com.aivle.be.warehousezone.dto.WarehouseZoneUpdateRequest;
import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseZoneService {

    private final WarehouseZoneRepository warehouseZoneRepository;
    private final WarehouseRepository warehouseRepository;

    @Transactional
    public WarehouseZoneResponse createZone(WarehouseZoneCreateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 창고입니다."));

        WarehouseZone zone = WarehouseZone.create(
                warehouse,
                request.getName(),
                request.getZoneType(),
                request.getDescription(),
                request.getMinX(),
                request.getMaxX(),
                request.getMinY(),
                request.getMaxY()
        );

        return WarehouseZoneResponse.from(
                warehouseZoneRepository.save(zone)
        );
    }

    public WarehouseZoneResponse getZone(Long zoneId) {
        WarehouseZone zone = warehouseZoneRepository.findById(zoneId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 구역입니다."));

        return WarehouseZoneResponse.from(zone);
    }

    public List<WarehouseZoneResponse> getZones() {
        return warehouseZoneRepository.findAll()
                .stream()
                .map(WarehouseZoneResponse::from)
                .toList();
    }

    @Transactional
    public WarehouseZoneResponse updateZone(
            Long zoneId,
            WarehouseZoneUpdateRequest request
    ) {
        WarehouseZone zone = warehouseZoneRepository.findById(zoneId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 구역입니다."));

        zone.update(
                request.getName(),
                request.getZoneType(),
                request.getDescription(),
                request.getMinX(),
                request.getMaxX(),
                request.getMinY(),
                request.getMaxY()
        );

        return WarehouseZoneResponse.from(zone);
    }

    @Transactional
    public void deleteZone(Long zoneId) {
        WarehouseZone zone = warehouseZoneRepository.findById(zoneId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 구역입니다."));

        warehouseZoneRepository.delete(zone);
    }
}