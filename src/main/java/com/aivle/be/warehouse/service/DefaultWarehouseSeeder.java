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

/**
 * 앱이 처음 켜질 때 기본 창고 3개를 넣는다.
 *
 * <p>예전에는 파이썬 스크립트가 지도 JSON 을 SQL 로 바꿔 두고
 * 그 SQL 을 시작할 때 실행했다. 그런데 화면에서 창고를 추가하는 기능이 생기면서
 * 같은 변환 규칙이 파이썬과 자바 두 곳에 생겼고,
 * 한쪽만 고치면 기본 창고와 사용자 창고가 서로 다르게 만들어지는 문제가 있었다.
 *
 * <p>그래서 기본 창고도 화면 업로드와 똑같이 {@link WarehouseImportService} 를 태운다.
 * 변환 규칙은 이제 한 곳에만 있고, 지도가 바뀌면 {@code db/maps} 의 JSON 만 갈아끼우면 된다.
 *
 * <p>공용 창고가 하나라도 있으면 아무 일도 하지 않는다.
 * 공용 창고는 화면에서 지울 수 없으므로 한 번 들어가면 계속 남아 있다.
 */
@Component
@RequiredArgsConstructor
public class DefaultWarehouseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultWarehouseSeeder.class);

    /** V01_base.sql 이 넣어 두는 관리자 계정 */
    private static final long ADMIN_USER_ID = 1L;

    private static final int DEFAULT_ROBOT_COUNT = 6;

    private static final String LOCATION = "대전광역시 유성구";

    /**
     * 넣을 기본 창고.
     *
     * <p>ID 를 못 박는 이유: 화면이 마지막으로 고른 창고를 ID 로 기억하고,
     * 기본값도 1번 창고다. 실행할 때마다 ID 가 달라지면 엉뚱한 창고를 보게 된다.
     */
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
                // 한 창고가 실패해도 나머지는 넣는다. 앱은 계속 뜨게 둔다.
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

        WarehouseImportResponse response = warehouseImportService.importWarehouse(
                request, ADMIN_USER_ID, true, spec.id()
        );

        log.info("[기본 창고] {} (id={}) 노드 {}, 간선 {}, 랙 {}, 충전소 {}, 로봇 {}",
                response.name(), response.warehouseId(), response.nodeCount(),
                response.edgeCount(), response.rackCount(),
                response.chargingStationCount(), response.robotCount());
    }

    /** 지도 JSON 에서 우리가 쓰는 nodes / edges 만 읽는다. */
    private WarehouseImportRequest.MapPayload readMap(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);

        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, WarehouseImportRequest.MapPayload.class);
        }
    }
}
