package com.aivle.be.warehouse.service;

import java.util.List;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.dto.WarehouseUpdateRequest;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.dto.WarehouseCreateRequest;
import com.aivle.be.warehouse.dto.WarehouseResponse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public WarehouseResponse createWarehouse(WarehouseCreateRequest request) {
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Warehouse warehouse = Warehouse.create(
                request.getName(),
                request.getWidth(),
                request.getHeight(),
                user,
                request.getLocation(),
                request.getDescription(),
                request.getStatus()
        );

        Warehouse savedWarehouse = warehouseRepository.save(warehouse);

        return WarehouseResponse.from(savedWarehouse);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        return WarehouseResponse.from(warehouse);
    }
    @Transactional(readOnly = true)
    public List<WarehouseResponse> getWarehouses(Long userId) {
        List<Warehouse> warehouses = userId == null
                ? warehouseRepository.findShared()
                : warehouseRepository.findVisibleTo(userId);

        return warehouses.stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    public WarehouseResponse updateWarehouse(
            Long warehouseId,
            WarehouseUpdateRequest request
    ) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        requireEditable(warehouse);

        warehouse.update(
                request.getName(),
                request.getWidth(),
                request.getHeight(),
                request.getLocation(),
                request.getDescription(),
                request.getStatus()
        );

        return WarehouseResponse.from(warehouse);
    }

    public void deleteWarehouse(Long warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        requireEditable(warehouse);

        // 참조하는 쪽 -> 참조받는 쪽 순서
        String[] statements = {
                "DELETE FROM event WHERE warehouse_id = ?",
                "DELETE FROM task WHERE warehouse_id = ?",
                "DELETE FROM simulation_run_robots WHERE simulation_run_id IN "
                        + "(SELECT simulation_run_id FROM simulation_runs WHERE warehouse_id = ?)",
                "DELETE FROM simulation_runs WHERE warehouse_id = ?",
                "DELETE FROM simulation WHERE warehouse_id = ?",
                "DELETE FROM scenario WHERE warehouse_id = ?",
                "DELETE FROM warehouse_items WHERE warehouse_id = ?",
                "DELETE FROM storage_location WHERE warehouse_id = ?",
                "DELETE FROM charging_station WHERE warehouse_id = ?",
                "DELETE FROM robot WHERE warehouse_id = ?",
                "DELETE FROM warehouse_edge WHERE from_node_id IN "
                        + "(SELECT node_id FROM warehouse_node WHERE warehouse_id = ?)",
                "DELETE FROM warehouse_node WHERE warehouse_id = ?",
                "DELETE FROM warehouse_zone WHERE warehouse_id = ?",
        };

        for (String statement : statements) {
            jdbcTemplate.update(statement, warehouseId);
        }

        warehouseRepository.delete(warehouse);
    }

    private void requireEditable(Warehouse warehouse) {
        if (warehouse.isShared()) {
            throw new BusinessException(ErrorCode.SHARED_WAREHOUSE_READ_ONLY);
        }
    }
}