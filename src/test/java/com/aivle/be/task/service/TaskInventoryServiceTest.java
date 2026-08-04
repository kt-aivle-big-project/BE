package com.aivle.be.task.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.entity.Task;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousezone.service.WarehouseZoneResolverService;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskInventoryServiceTest {

    private final WarehouseItemRepository warehouseItemRepository = mock(WarehouseItemRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final WarehouseZoneResolverService warehouseZoneResolverService = mock(WarehouseZoneResolverService.class);

    private TaskInventoryService service;
    private Warehouse warehouse;
    private WarehouseNode targetNode;
    private StorageLocation targetLocation;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new TaskInventoryService(
                warehouseItemRepository,
                productRepository,
                storageLocationRepository,
                warehouseZoneResolverService
        );
        warehouse = mock(Warehouse.class);
        targetNode = mock(WarehouseNode.class);
        targetLocation = mock(StorageLocation.class);
        product = mock(Product.class);

        when(warehouse.getId()).thenReturn(1L);
        when(targetNode.getId()).thenReturn(20L);
        when(targetLocation.getId()).thenReturn(55L);
        when(product.getUnitsPerBox()).thenReturn(20);
        when(productRepository.findById(100L)).thenReturn(Optional.of(product));
        when(storageLocationRepository.findByWarehouse_IdAndNode_Id(1L, 20L))
                .thenReturn(Optional.of(targetLocation));
        when(warehouseItemRepository.save(any(WarehouseItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void inboundCompletionUsesTheRackLevelSelectedByThePlan() {
        Task task = inboundTaskAtLevel(3);

        service.applyCompletion(task);

        ArgumentCaptor<WarehouseItem> captor = ArgumentCaptor.forClass(WarehouseItem.class);
        verify(warehouseItemRepository).save(captor.capture());
        assertThat(captor.getValue().getRackLevel()).isEqualTo(3);
        assertThat(captor.getValue().getQuantity()).isEqualTo(2);
        verify(warehouseItemRepository)
                .existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(55L, 3, 0);
        verify(warehouseItemRepository, never())
                .existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(55L, 1, 0);
    }

    @Test
    void inboundCompletionRejectsAnOccupiedPlannedRackLevel() {
        Task task = inboundTaskAtLevel(2);
        when(warehouseItemRepository.existsByStorageLocation_IdAndRackLevelAndQuantityGreaterThan(55L, 2, 0))
                .thenReturn(true);

        assertThatThrownBy(() -> service.applyCompletion(task))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(warehouseItemRepository, never()).save(any(WarehouseItem.class));
    }

    @Test
    void inboundInventoryIsAppliedOnceOnlyAfterRackDropCompletes() {
        Task task = inboundTaskAtLevel(1);

        assertThat(service.applyForServiceCompletion(task, "PICKUP")).isFalse();
        assertThat(service.applyForServiceCompletion(task, "DROP")).isTrue();
        assertThat(service.applyForServiceCompletion(task, "DROP")).isFalse();

        verify(warehouseItemRepository).save(any(WarehouseItem.class));
        assertThat(task.isInventoryApplied()).isTrue();
    }

    private Task inboundTaskAtLevel(int rackLevel) {
        Task task = new Task(
                warehouse,
                mock(WarehouseNode.class),
                targetNode,
                TaskType.INBOUND,
                null,
                null,
                2,
                100L
        );
        task.reserveTargetRackLevel(rackLevel);
        return task;
    }
}
