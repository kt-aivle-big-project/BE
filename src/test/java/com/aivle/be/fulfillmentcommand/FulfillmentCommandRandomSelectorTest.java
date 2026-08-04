package com.aivle.be.fulfillmentcommand;

import com.aivle.be.fulfillmentcommand.controller.request.FulfillmentCommandGenerateRequest;
import com.aivle.be.fulfillmentcommand.domain.CommandExpressionMode;
import com.aivle.be.fulfillmentcommand.domain.CommandPolicyProfile;
import com.aivle.be.fulfillmentcommand.domain.FulfillmentCommandMode;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandRandomSelector;
import com.aivle.be.fulfillmentcommand.service.FulfillmentCommandSelection;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.simulationrun.repository.SimulationRunRobotRepository;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentCommandRandomSelectorTest {

    @Test
    void eachCallCreatesAFeasibleSelectionWithoutUsingTheRunSeed() {
        SimulationRunRepository runRepository = mock(SimulationRunRepository.class);
        SimulationRunRobotRepository participantRepository = mock(SimulationRunRobotRepository.class);
        ProductRepository productRepository = mock(ProductRepository.class);
        WarehouseItemRepository itemRepository = mock(WarehouseItemRepository.class);
        StorageLocationRepository locationRepository = mock(StorageLocationRepository.class);
        WarehouseNodeRepository nodeRepository = mock(WarehouseNodeRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        Warehouse warehouse = mock(Warehouse.class);
        when(warehouse.getId()).thenReturn(1L);
        SimulationRun run = mock(SimulationRun.class);
        when(run.getWarehouse()).thenReturn(warehouse);
        when(runRepository.findById(9L)).thenReturn(Optional.of(run));

        Product product = mock(Product.class);
        when(product.getProductCode()).thenReturn("ITEM-001");
        WarehouseItem item = mock(WarehouseItem.class);
        when(item.getId()).thenReturn(77L);
        when(item.getQuantity()).thenReturn(20);
        when(item.getProduct()).thenReturn(product);
        when(productRepository.findAllByOrderByProductCodeAsc()).thenReturn(List.of(product));
        when(itemRepository.findAllByWarehouse_Id(1L)).thenReturn(List.of(item));
        when(locationRepository.findAllByWarehouse_Id(1L)).thenReturn(
                List.of(mock(StorageLocation.class), mock(StorageLocation.class))
        );
        when(nodeRepository.findAllByWarehouse_IdAndNodeTypeAndActiveTrue(eq(1L), any()))
                .thenReturn(List.of(mock(WarehouseNode.class)));
        when(taskRepository.findAllBySimulationRun_IdAndStatusInOrderByRequestedAtAsc(eq(9L), any()))
                .thenReturn(List.of());
        when(participantRepository.findAllBySimulationRun_IdOrderByRobot_Id(9L))
                .thenReturn(List.of(mock(com.aivle.be.simulationrun.entity.SimulationRunRobot.class)));

        FulfillmentCommandRandomSelector selector = new FulfillmentCommandRandomSelector(
                runRepository,
                participantRepository,
                productRepository,
                itemRepository,
                locationRepository,
                nodeRepository,
                taskRepository
        );
        assertThat(ReflectionTestUtils.getField(selector, "workloadMultiplierWeights"))
                .isEqualTo("10,15,20,25,30");
        FulfillmentCommandGenerateRequest request = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.BOTH,
                1,
                1,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.AUTO,
                CommandPolicyProfile.AUTO,
                true,
                true
        );

        FulfillmentCommandSelection selection = selector.select(9L, request);
        FulfillmentCommandSelection nextSelection = selector.select(9L, request);

        assertThat(selection.operations()).extracting(FulfillmentCommandSelection.Operation::operationType)
                .containsExactly("INBOUND", "OUTBOUND");
        assertThat(selection.operations().get(1).warehouseItemId()).isEqualTo(77L);
        assertThat(selection.selectionSeed()).isPositive();
        assertThat(nextSelection.selectionSeed()).isNotEqualTo(selection.selectionSeed());
        verify(run, never()).getRandomSeed();

        List<WarehouseItem> tenOutboundBoxes = new ArrayList<>();
        for (long itemId = 101L; itemId <= 110L; itemId++) {
            WarehouseItem outboundBox = mock(WarehouseItem.class);
            when(outboundBox.getId()).thenReturn(itemId);
            when(outboundBox.getQuantity()).thenReturn(20);
            when(outboundBox.getProduct()).thenReturn(product);
            tenOutboundBoxes.add(outboundBox);
        }
        when(itemRepository.findAllByWarehouse_Id(1L)).thenReturn(tenOutboundBoxes);
        when(participantRepository.findAllBySimulationRun_IdOrderByRobot_Id(9L))
                .thenReturn(List.of(
                        mock(com.aivle.be.simulationrun.entity.SimulationRunRobot.class),
                        mock(com.aivle.be.simulationrun.entity.SimulationRunRobot.class)
                ));
        ReflectionTestUtils.setField(
                selector,
                "workloadMultiplierWeights",
                "0,0,0,0,100"
        );
        FulfillmentCommandGenerateRequest perRobotFiveTimesRequest = new FulfillmentCommandGenerateRequest(
                FulfillmentCommandMode.OUTBOUND,
                null,
                null,
                null,
                null,
                "medium",
                0L,
                CommandExpressionMode.STRUCTURED_ONLY,
                CommandPolicyProfile.AUTO,
                false,
                false
        );

        FulfillmentCommandSelection perRobotFiveTimesSelection = selector.select(
                9L,
                perRobotFiveTimesRequest
        );

        assertThat(perRobotFiveTimesSelection.outboundCount()).isEqualTo(10);
        assertThat(perRobotFiveTimesSelection.operations()).hasSize(10);
    }
}
