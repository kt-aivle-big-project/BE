package com.aivle.be.graph.event;

import com.aivle.be.graph.service.GraphSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Keeps the shared Neo4j projection current after BE map changes commit. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarehouseGraphSyncListener {

    private final GraphSyncService graphSyncService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sync(WarehouseGraphChangedEvent event) {
        try {
            graphSyncService.syncWarehouseGraph(event.warehouseId());
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to publish warehouse {} to the shared LARO Neo4j graph",
                    event.warehouseId(),
                    exception
            );
        }
    }
}
