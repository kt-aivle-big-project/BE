package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.repository.TaskRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.dto.WarehouseImportRequest;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarehouseImportScenarioPresetTest {

    private static final long USER_ID = 7L;

    @Mock private WarehouseRepository warehouseRepository;
    @Mock private WarehouseNodeRepository warehouseNodeRepository;
    @Mock private WarehouseEdgeRepository warehouseEdgeRepository;
    @Mock private WarehouseZoneRepository warehouseZoneRepository;
    @Mock private ChargingStationRepository chargingStationRepository;
    @Mock private StorageLocationRepository storageLocationRepository;
    @Mock private RobotRepository robotRepository;
    @Mock private RobotSpecRepository robotSpecRepository;
    @Mock private ScenarioRepository scenarioRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProductRepository productRepository;
    @Mock private WarehouseItemRepository warehouseItemRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private WarehouseFacilitySyncService warehouseFacilitySyncService;
    @InjectMocks private WarehouseImportService warehouseImportService;

    @BeforeEach
    void setUp() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(mock(User.class)));
        when(warehouseRepository.save(any(Warehouse.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(warehouseNodeRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storageLocationRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findAllByOrderByProductCodeAsc())
                .thenReturn(List.of());
    }

    @Test
    void personalWarehouseImportDoesNotCreateScenarioPresets() {
        warehouseImportService.importWarehouse(request(), USER_ID);

        verifyNoInteractions(scenarioRepository);
    }

    @Test
    void defaultWarehouseImportStillCreatesThreeScenarioPresets() {
        warehouseImportService.importWarehouseWithScenarioPresets(
                request(),
                USER_ID
        );

        verify(scenarioRepository).saveAll(anyList());
    }

    private WarehouseImportRequest request() {
        WarehouseImportRequest.MapNode route = new WarehouseImportRequest.MapNode(
                "R0",
                "route",
                0.0,
                0.0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                true,
                1,
                true,
                false,
                Map.of()
        );

        return new WarehouseImportRequest(
                "개인 창고",
                10,
                10,
                USER_ID,
                "대전",
                "테스트 창고",
                Warehouse.WarehouseStatus.ACTIVE,
                5,
                new WarehouseImportRequest.MapPayload(
                        List.of(route),
                        List.of()
                )
        );
    }
}
