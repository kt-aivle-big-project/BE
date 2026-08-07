package com.aivle.be.simulationrun.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.laro.client.LaroPlanClient;
import com.aivle.be.laro.service.LaroInventoryReservationService;
import com.aivle.be.laro.service.LaroPlanExecutionService;
import com.aivle.be.laro.service.LaroPlanService;
import com.aivle.be.laro.service.LaroReplanStateService;
import com.aivle.be.optimization.client.OptimizationClient;
import com.aivle.be.optimization.dto.request.OptimizationRequest;
import com.aivle.be.optimization.repository.OptimizationResultRepository;
import com.aivle.be.optimization.service.OptimizationService;
import com.aivle.be.simulationrun.entity.SimulationRun;
import com.aivle.be.simulationrun.playback.SimulationPlaybackService;
import com.aivle.be.simulationrun.repository.SimulationRunRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiTemplateWarehouseExecutionTest {

    @Mock private LaroPlanClient laroPlanClient;
    @Mock private LaroPlanExecutionService executionService;
    @Mock private LaroReplanStateService replanStateService;
    @Mock private SimulationPlaybackService playbackService;
    @Mock private LaroInventoryReservationService inventoryReservationService;
    @Mock private SimulationRunRepository simulationRunRepository;
    @Mock private OptimizationClient optimizationClient;
    @Mock private OptimizationResultRepository optimizationResultRepository;
    @Mock private WarehouseRepository warehouseRepository;

    private Warehouse template;

    @BeforeEach
    void setUp() {
        template = Warehouse.create(
                "template",
                10,
                10,
                new User("owner@test.com", "owner", "hash")
        );
        template.markShared();
    }

    @Test
    void directLaroPlanAndReplanRejectTemplateWarehouseBeforeAiCall() {
        SimulationRun run = SimulationRun.create(template, LocalDateTime.now());
        when(simulationRunRepository.findByIdWithWarehouse(100L))
                .thenReturn(Optional.of(run));
        LaroPlanService service = new LaroPlanService(
                laroPlanClient,
                executionService,
                replanStateService,
                playbackService,
                inventoryReservationService,
                simulationRunRepository,
                1_000L
        );

        assertTemplateError(() -> service.plan(100L, null));
        assertTemplateError(() -> service.replan(100L, null));

        verifyNoInteractions(laroPlanClient, playbackService);
    }

    @Test
    void directOptimizationRejectsTemplateWarehouseBeforeAiCall() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(template));
        OptimizationService service = new OptimizationService(
                optimizationClient,
                optimizationResultRepository,
                warehouseRepository
        );

        assertTemplateError(() -> service.optimize(new OptimizationRequest(
                1L,
                List.of(),
                List.of(),
                List.of()
        )));

        verifyNoInteractions(optimizationClient, optimizationResultRepository);
    }

    private void assertTemplateError(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.TEMPLATE_WAREHOUSE_NOT_EXECUTABLE
                        )
                );
    }
}
