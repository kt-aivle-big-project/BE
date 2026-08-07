package com.aivle.be.graph.event;

import com.aivle.be.graph.service.GraphSyncService;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/* 초기 창고 데이터 graph sync 자동 처리 */

@Slf4j
@Component
@RequiredArgsConstructor
public class WarehouseGraphBootstrapper {

    private final WarehouseRepository warehouseRepository;
    private final GraphSyncService graphSyncService;

    @EventListener(ApplicationReadyEvent.class)
    public void syncSharedWarehouses() {
        List<Warehouse> sharedWarehouses = warehouseRepository.findShared();
        if (sharedWarehouses.isEmpty()) {
            log.info("시작 시 공용 창고가 없어 그래프 부트스트랩 동기화를 건너뜁니다.");
            return;
        }

        for (Warehouse warehouse : sharedWarehouses) {
            try {
                graphSyncService.syncWarehouseGraph(warehouse.getId());
                log.info(
                        "공용 창고 그래프 동기화 완료: warehouseId={}, name={}",
                        warehouse.getId(),
                        warehouse.getName()
                );
            } catch (RuntimeException exception) {
                log.error(
                        "공용 창고 그래프 부트스트랩 동기화 실패: warehouseId={}, name={}",
                        warehouse.getId(),
                        warehouse.getName(),
                        exception
                );
            }
        }
    }
}
