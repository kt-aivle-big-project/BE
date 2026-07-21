package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.dto.response.ChargingStationResponse;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.warehouse.dto.WarehouseLayoutResponse;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.dto.WarehouseEdgeResponse;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehousenode.dto.WarehouseNodeResponse;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehousezone.dto.WarehouseZoneResponse;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseLayoutService {

    private final WarehouseRepository warehouseRepository;
    private final WarehouseZoneRepository warehouseZoneRepository;
    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseEdgeRepository warehouseEdgeRepository;
    private final ChargingStationRepository chargingStationRepository;

    public WarehouseLayoutResponse getLayout(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "창고를 찾을 수 없습니다."
                ));

        List<WarehouseZoneResponse> zones =
                warehouseZoneRepository.findAllByWarehouse_Id(warehouseId)
                        .stream()
                        .map(WarehouseZoneResponse::from)
                        .toList();

        List<WarehouseNodeResponse> nodes =
                warehouseNodeRepository.findAllByWarehouse_Id(warehouseId)
                        .stream()
                        .map(WarehouseNodeResponse::from)
                        .toList();

        List<WarehouseEdgeResponse> edges =
                warehouseEdgeRepository.findAllByFromNode_Warehouse_Id(warehouseId)
                        .stream()
                        .map(WarehouseEdgeResponse::from)
                        .toList();

        List<ChargingStationResponse> chargingStations =
                chargingStationRepository.findAllByWarehouse_Id(warehouseId)
                        .stream()
                        .map(ChargingStationResponse::from)
                        .toList();

        return new WarehouseLayoutResponse(
                WarehouseResponse.from(warehouse),
                zones,
                nodes,
                edges,
                chargingStations
        );
    }
}