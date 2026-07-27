package com.aivle.be.storagelocation.repository;

import com.aivle.be.storagelocation.entity.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {

    Optional<StorageLocation> findByWarehouse_IdAndNode_Id(Long warehouseId, Long nodeId);

    List<StorageLocation> findAllByWarehouse_Id(Long warehouseId);
}
