package com.aivle.be.warehouse.service;

import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import com.aivle.be.warehouse.dto.WarehouseImportResponse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultWarehouseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultWarehouseSeeder.class);

    /** V01_base.sql 이 넣어 두는 관리자 계정 */
    private static final long ADMIN_USER_ID = 1L;

    private static final int DEFAULT_ROBOT_COUNT = 6;

    private static final String LOCATION = "대전광역시 유성구";

    private record DefaultWarehouse(
            long id,
            String file,
            String name,
            int width,
            int height,
            String description
    ) {}

    private static final List<DefaultWarehouse> DEFAULTS = List.of(
            new DefaultWarehouse(1, "db/maps/warehouse_1.json",
                    "대전 물류센터 A (기본형)", 13, 7,
                    "자동창고 노드-엣지 구조 - 기본형 창고 맵"),
            new DefaultWarehouse(2, "db/maps/warehouse_2.json",
                    "대전 물류센터 B (순환형)", 19, 12,
                    "자동창고 노드-엣지 구조 - 순환형 창고 맵"),
            new DefaultWarehouse(3, "db/maps/warehouse_3.json",
                    "대전 물류센터 C (지그재그형)", 13, 8,
                    "자동창고 노드-엣지 구조 - 지그재그형 창고 맵")
    );

    private final WarehouseRepository warehouseRepository;
    private final WarehouseImportService warehouseImportService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!warehouseRepository.findShared().isEmpty()) {
            log.debug("[기본 창고] 이미 있어 건너뜁니다.");
            return;
        }

        for (DefaultWarehouse spec : DEFAULTS) {
            try {
                seed(spec);
            } catch (Exception exception) {
                log.error("[기본 창고] {} 생성 실패 - {}", spec.name(), exception.getMessage(), exception);
            }
        }
    }

    private void seed(DefaultWarehouse spec) throws Exception {
        WarehouseImportRequest.MapPayload map = readMap(spec.file());

        WarehouseImportRequest request = new WarehouseImportRequest(
                spec.name(),
                spec.width(),
                spec.height(),
                ADMIN_USER_ID,
                LOCATION,
                spec.description(),
                null,
                DEFAULT_ROBOT_COUNT,
                map
        );

        WarehouseImportResponse response =
                warehouseImportService.importWarehouseWithScenarioPresets(
                        request, ADMIN_USER_ID
                );

        log.info("[기본 창고] {} (id={}) 노드 {}, 간선 {}, 랙 {}, 충전소 {}, 로봇 {}",
                response.name(), response.warehouseId(), response.nodeCount(),
                response.edgeCount(), response.rackCount(),
                response.chargingStationCount(), response.robotCount());
    }

    private WarehouseImportRequest.MapPayload readMap(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);

        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, WarehouseImportRequest.MapPayload.class);
        }
    }
}
