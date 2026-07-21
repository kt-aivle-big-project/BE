package com.aivle.be.chargingstation.repository;

import com.aivle.be.chargingstation.entity.ChargingStation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChargingStationRepository
        extends JpaRepository<ChargingStation, Long> {

    List<ChargingStation> findAllByWarehouse_Id(Long warehouseId);
}