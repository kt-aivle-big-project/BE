package com.aivle.be.chargingstation.service;

import com.aivle.be.chargingstation.dto.request.ChargingStationRequest;
import com.aivle.be.chargingstation.dto.response.ChargingStationResponse;
import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChargingStationService {

    private final ChargingStationRepository chargingStationRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;

    @Transactional
    public ChargingStationResponse createChargingStation(
            ChargingStationRequest request
    ) {
        Warehouse warehouse = warehouseRepository.findById(request.warehouseId())
                .orElseThrow(() ->
                        new IllegalArgumentException("존재하지 않는 창고입니다.")
                );

        WarehouseNode node = warehouseNodeRepository.findById(request.nodeId())
                .orElseThrow(() ->
                        new IllegalArgumentException("존재하지 않는 노드입니다.")
                );

        if (!node.getWarehouse().getId().equals(warehouse.getId())) {
            throw new IllegalArgumentException(
                    "해당 노드는 선택한 창고에 속하지 않습니다."
            );
        }

        ChargingStation chargingStation = ChargingStation.create(
                warehouse,
                node,
                request.name(),
                request.status(),
                request.chargingPower()
        );

        return ChargingStationResponse.from(
                chargingStationRepository.save(chargingStation)
        );
    }

    public ChargingStationResponse getChargingStation(Long chargingStationId) {
        ChargingStation chargingStation =
                findChargingStation(chargingStationId);

        return ChargingStationResponse.from(chargingStation);
    }

    public List<ChargingStationResponse> getChargingStations() {
        return chargingStationRepository.findAll()
                .stream()
                .map(ChargingStationResponse::from)
                .toList();
    }

    @Transactional
    public ChargingStationResponse updateChargingStation(
            Long chargingStationId,
            ChargingStationRequest request
    ) {
        ChargingStation chargingStation =
                findChargingStation(chargingStationId);

        chargingStation.update(
                request.name(),
                request.status(),
                request.chargingPower()
        );

        return ChargingStationResponse.from(chargingStation);
    }

    @Transactional
    public void deleteChargingStation(Long chargingStationId) {
        ChargingStation chargingStation =
                findChargingStation(chargingStationId);

        chargingStationRepository.delete(chargingStation);
    }

    private ChargingStation findChargingStation(Long chargingStationId) {
        return chargingStationRepository.findById(chargingStationId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "존재하지 않는 충전소입니다."
                        )
                );
    }
}