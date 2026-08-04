package com.aivle.be.warehouse.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Stores the authoritative raw map used by both BE and AI. */
@Service
@RequiredArgsConstructor
public class WarehouseMapContractStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS warehouse_map_contract (
                    warehouse_id BIGINT PRIMARY KEY,
                    map_payload JSONB NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
    }

    public void save(Long warehouseId, WarehouseImportRequest.MapPayload map) {
        String payload = objectMapper.writeValueAsString(map);
        jdbcTemplate.update("""
                INSERT INTO warehouse_map_contract
                    (warehouse_id, map_payload, updated_at)
                VALUES (?, ?::jsonb, now())
                ON CONFLICT (warehouse_id) DO UPDATE
                SET map_payload = EXCLUDED.map_payload,
                    updated_at = now()
                """, warehouseId, payload);
    }

    public WarehouseImportRequest.MapPayload load(Long warehouseId) {
        return jdbcTemplate.query(
                "SELECT map_payload::text FROM warehouse_map_contract WHERE warehouse_id = ?",
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new BusinessException(ErrorCode.WAREHOUSE_MAP_CONTRACT_NOT_FOUND);
                    }
                    return objectMapper.readValue(
                            resultSet.getString(1),
                            WarehouseImportRequest.MapPayload.class
                    );
                },
                warehouseId
        );
    }
}
