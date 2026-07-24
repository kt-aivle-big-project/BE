package com.aivle.be.storagelocation.repository;

import com.aivle.be.storagelocation.entity.StorageLocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageLocationRepository extends JpaRepository<StorageLocation, Long> {
}
