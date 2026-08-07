package com.aivle.be.warehouse.service;

import com.aivle.be.chargingstation.entity.ChargingStation;
import com.aivle.be.chargingstation.repository.ChargingStationRepository;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.graph.event.WarehouseGraphChangedEvent;
import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.robot.domain.RobotAvailabilityStatus;
import com.aivle.be.robot.entity.Robot;
import com.aivle.be.robot.repository.RobotRepository;
import com.aivle.be.robotspec.entity.RobotSpec;
import com.aivle.be.robotspec.repository.RobotSpecRepository;
import com.aivle.be.scenario.entity.Scenario;
import com.aivle.be.scenario.repository.ScenarioRepository;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import com.aivle.be.warehousezone.entity.WarehouseZone;
import com.aivle.be.warehousezone.repository.WarehouseZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.url=jdbc:h2:mem:warehouse-template-clone;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON"
})
@Import(WarehouseTemplateCloneService.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WarehouseTemplateCloneServiceTest {

    private static final String GUEST_A =
            "a4d70ea4-9a96-4c75-8414-24a43114a962";
    private static final String GUEST_B =
            "b5e81fb5-ab07-4d86-9525-35b54225ba73";

    @Autowired private WarehouseTemplateCloneService service;
    @Autowired private UserRepository userRepository;
    @Autowired private WarehouseRepository warehouseRepository;
    @Autowired private WarehouseZoneRepository warehouseZoneRepository;
    @Autowired private WarehouseNodeRepository warehouseNodeRepository;
    @Autowired private WarehouseEdgeRepository warehouseEdgeRepository;
    @Autowired private ChargingStationRepository chargingStationRepository;
    @Autowired private StorageLocationRepository storageLocationRepository;
    @Autowired private WarehouseItemRepository warehouseItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private RobotSpecRepository robotSpecRepository;
    @Autowired private RobotRepository robotRepository;
    @MockitoSpyBean private ScenarioRepository scenarioRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents applicationEvents;

    @BeforeEach
    void removeH2RobotConverterCheckConstraint() {
        jdbcTemplate.queryForList(
                """
                select constraint_name
                from information_schema.table_constraints
                where table_name = 'ROBOT'
                  and constraint_type = 'CHECK'
                """,
                String.class
        ).forEach(name -> jdbcTemplate.execute(
                "alter table robot drop constraint " + name
        ));
    }

    @Test
    void createsDeepPersonalCopyAndRemapsEveryWarehouseReference() {
        Fixture fixture = inTransaction(this::createFixture);

        Warehouse created = service.ensurePersonalCopy(
                fixture.templateId(),
                fixture.userAId()
        );

        assertThat(created.getId()).isNotEqualTo(fixture.templateId());
        assertThat(applicationEvents.stream(WarehouseGraphChangedEvent.class))
                .singleElement()
                .extracting(WarehouseGraphChangedEvent::warehouseId)
                .isEqualTo(created.getId());
        inTransaction(() -> {
            Warehouse copy = warehouseRepository.findById(created.getId()).orElseThrow();
            assertThat(copy.isShared()).isFalse();
            assertThat(copy.getUser().getId()).isEqualTo(fixture.userAId());
            assertThat(copy.getSourceTemplate().getId()).isEqualTo(fixture.templateId());
            assertThat(warehouseZoneRepository.findAllByWarehouse_Id(copy.getId()))
                    .singleElement()
                    .satisfies(zone -> {
                        assertThat(zone.getName()).isEqualTo("storage");
                        assertThat(zone.getWarehouse().getId()).isEqualTo(copy.getId());
                    });

            List<WarehouseNode> sourceNodes = warehouseNodeRepository
                    .findAllByWarehouse_Id(fixture.templateId());
            List<WarehouseNode> copiedNodes = warehouseNodeRepository
                    .findAllByWarehouse_Id(copy.getId());
            assertThat(copiedNodes).hasSize(sourceNodes.size());
            assertThat(copiedNodes).extracting(WarehouseNode::getId)
                    .doesNotContainAnyElementsOf(
                            sourceNodes.stream().map(WarehouseNode::getId).toList()
                    );
            Map<String, WarehouseNode> copiedByCode = copiedNodes.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            WarehouseNode::getNodeCode,
                            node -> node
                    ));

            List<WarehouseEdge> edges = warehouseEdgeRepository
                    .findAllByFromNode_Warehouse_Id(copy.getId());
            assertThat(edges).hasSize(2).allSatisfy(edge -> {
                assertThat(edge.getFromNode().getWarehouse().getId()).isEqualTo(copy.getId());
                assertThat(edge.getToNode().getWarehouse().getId()).isEqualTo(copy.getId());
            });

            ChargingStation station = chargingStationRepository
                    .findAllByWarehouse_Id(copy.getId()).get(0);
            assertThat(station.getNode().getId())
                    .isEqualTo(copiedByCode.get("CHARGE-1").getId());

            StorageLocation storage = storageLocationRepository
                    .findAllByWarehouse_Id(copy.getId()).get(0);
            assertThat(storage.getNode().getId())
                    .isEqualTo(copiedByCode.get("RACK-1").getId());

            WarehouseItem item = warehouseItemRepository
                    .findAllByWarehouse_Id(copy.getId()).get(0);
            assertThat(item.getStorageLocation().getId()).isEqualTo(storage.getId());
            assertThat(item.getNode().getId()).isEqualTo(storage.getNode().getId());
            assertThat(item.getProduct().getId()).isEqualTo(fixture.productId());
            assertThat(item.getQuantity()).isEqualTo(12);
            assertThat(item.getInboundQuantity()).isEqualTo(5);
            assertThat(item.getOutboundQuantity()).isEqualTo(3);

            Robot robot = robotRepository.findAllByWarehouse_Id(copy.getId()).get(0);
            assertThat(robot.getRobotSpec().getId()).isEqualTo(fixture.robotSpecId());
            assertThat(robot.getNodeId())
                    .isEqualTo(copiedByCode.get("CHARGE-1").getId());

            Scenario scenario = scenarioRepository
                    .findAllByWarehouse_IdOrderByIdAsc(copy.getId()).get(0);
            assertThat(scenario.getDescription()).isEqualTo("scenario description");
            assertThat(scenario.getInitialBattery()).isEqualTo(85);
            assertThat(scenario.getMoveSecondsPerNode()).isEqualTo(3.0);
            assertThat(scenario.getPickingSeconds()).isEqualTo(4.0);
            assertThat(scenario.getLoadingSeconds()).isEqualTo(6.0);

            assertThat(productRepository.count()).isEqualTo(1);
            assertThat(robotSpecRepository.count()).isEqualTo(1);
            return null;
        });
    }

    @Test
    void repeatedRequestReturnsSameWarehouseAndAnotherUserGetsAnotherWarehouse() {
        Fixture fixture = inTransaction(this::createFixture);

        Warehouse first = service.ensurePersonalCopy(fixture.templateId(), fixture.userAId());
        Warehouse repeated = service.ensurePersonalCopy(fixture.templateId(), fixture.userAId());
        Warehouse otherUser = service.ensurePersonalCopy(fixture.templateId(), fixture.userBId());

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(otherUser.getId()).isNotEqualTo(first.getId());
        assertThat(warehouseRepository.existsByUser_IdAndSourceTemplate_Id(
                fixture.userAId(), fixture.templateId())).isTrue();
        assertThat(warehouseRepository.existsByUser_IdAndSourceTemplate_Id(
                fixture.userBId(), fixture.templateId())).isTrue();
        assertThat(applicationEvents.stream(WarehouseGraphChangedEvent.class))
                .extracting(WarehouseGraphChangedEvent::warehouseId)
                .containsExactly(first.getId(), otherUser.getId());
    }

    @Test
    void guestCopyIsDeepIdempotentPerSessionAndIsolatedBetweenGuests() {
        Fixture fixture = inTransaction(this::createFixture);

        Warehouse first = service.ensureGuestPersonalCopy(
                fixture.templateId(),
                GUEST_A
        );
        Warehouse repeated = service.ensureGuestPersonalCopy(
                fixture.templateId(),
                GUEST_A
        );
        Warehouse otherGuest = service.ensureGuestPersonalCopy(
                fixture.templateId(),
                GUEST_B
        );

        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(otherGuest.getId()).isNotEqualTo(first.getId());
        assertThat(applicationEvents.stream(WarehouseGraphChangedEvent.class))
                .extracting(WarehouseGraphChangedEvent::warehouseId)
                .containsExactly(first.getId(), otherGuest.getId());

        inTransaction(() -> {
            Warehouse copy = warehouseRepository.findById(first.getId()).orElseThrow();
            assertThat(copy.getUser()).isNull();
            assertThat(copy.getGuestSessionId()).isEqualTo(GUEST_A);
            assertThat(copy.isShared()).isFalse();
            assertThat(copy.getSourceTemplate().getId()).isEqualTo(fixture.templateId());

            List<WarehouseNode> sourceNodes = warehouseNodeRepository
                    .findAllByWarehouse_Id(fixture.templateId());
            List<WarehouseNode> copiedNodes = warehouseNodeRepository
                    .findAllByWarehouse_Id(copy.getId());
            assertThat(copiedNodes).hasSameSizeAs(sourceNodes);
            assertThat(copiedNodes).extracting(WarehouseNode::getId)
                    .doesNotContainAnyElementsOf(
                            sourceNodes.stream().map(WarehouseNode::getId).toList()
                    );
            assertThat(warehouseZoneRepository.findAllByWarehouse_Id(copy.getId()))
                    .hasSize(1);
            assertThat(warehouseEdgeRepository
                    .findAllByFromNode_Warehouse_Id(copy.getId()))
                    .hasSize(2)
                    .allSatisfy(edge -> {
                        assertThat(edge.getFromNode().getWarehouse().getId())
                                .isEqualTo(copy.getId());
                        assertThat(edge.getToNode().getWarehouse().getId())
                                .isEqualTo(copy.getId());
                    });
            assertThat(chargingStationRepository.findAllByWarehouse_Id(copy.getId()))
                    .hasSize(1);
            assertThat(storageLocationRepository.findAllByWarehouse_Id(copy.getId()))
                    .hasSize(1);
            assertThat(warehouseItemRepository.findAllByWarehouse_Id(copy.getId()))
                    .hasSize(1);
            assertThat(robotRepository.findAllByWarehouse_Id(copy.getId()))
                    .hasSize(1);
            assertThat(scenarioRepository
                    .findAllByWarehouse_IdOrderByIdAsc(copy.getId()))
                    .singleElement()
                    .satisfies(scenario -> {
                        assertThat(scenario.getDescription())
                                .isEqualTo("scenario description");
                        assertThat(scenario.getInitialBattery()).isEqualTo(85);
                    });
            assertThat(productRepository.count()).isEqualTo(1);
            assertThat(robotSpecRepository.count()).isEqualTo(1);
            return null;
        });
    }

    @Test
    void guestCopyRejectsMissingNonSharedTemplateAndMissingSession() {
        Fixture fixture = inTransaction(this::createFixture);
        Long customWarehouseId = inTransaction(() -> warehouseRepository.save(
                Warehouse.create(
                        "custom",
                        5,
                        5,
                        userRepository.findById(fixture.userAId()).orElseThrow()
                )
        ).getId());

        assertError(
                () -> service.ensureGuestPersonalCopy(999_999L, GUEST_A),
                ErrorCode.WAREHOUSE_NOT_FOUND
        );
        assertError(
                () -> service.ensureGuestPersonalCopy(customWarehouseId, GUEST_A),
                ErrorCode.WAREHOUSE_NOT_TEMPLATE
        );
        assertError(
                () -> service.ensureGuestPersonalCopy(fixture.templateId(), " "),
                ErrorCode.ACCESS_DENIED
        );
    }

    @Test
    void rejectsMissingOrNonSharedTemplate() {
        Fixture fixture = inTransaction(this::createFixture);
        Long customWarehouseId = inTransaction(() -> warehouseRepository.save(
                Warehouse.create("custom", 5, 5, userRepository.findById(fixture.userAId()).orElseThrow())
        ).getId());

        assertThatThrownBy(() -> service.ensurePersonalCopy(999_999L, fixture.userAId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND));
        assertThatThrownBy(() -> service.ensurePersonalCopy(customWarehouseId, fixture.userAId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_TEMPLATE));
    }

    @Test
    void rollsBackEveryCopiedRowWhenLaterStageFails() {
        Fixture fixture = inTransaction(this::createFixture);
        long warehousesBefore = warehouseRepository.count();
        long nodesBefore = warehouseNodeRepository.count();
        long itemsBefore = warehouseItemRepository.count();
        long robotsBefore = robotRepository.count();
        doThrow(new IllegalStateException("scenario copy failed"))
                .when(scenarioRepository).saveAll(anyList());

        assertThatThrownBy(() -> service.ensurePersonalCopy(
                fixture.templateId(),
                fixture.userAId()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(warehouseRepository.count()).isEqualTo(warehousesBefore);
        assertThat(warehouseNodeRepository.count()).isEqualTo(nodesBefore);
        assertThat(warehouseItemRepository.count()).isEqualTo(itemsBefore);
        assertThat(robotRepository.count()).isEqualTo(robotsBefore);
        assertThat(warehouseRepository.findByUser_IdAndSourceTemplate_Id(
                fixture.userAId(), fixture.templateId())).isEmpty();
        assertThat(applicationEvents.stream(WarehouseGraphChangedEvent.class)).isEmpty();
    }

    private Fixture createFixture() {
        User owner = userRepository.save(new User("owner@test.com", "owner", "hash"));
        User userA = userRepository.save(new User("a@test.com", "A", "hash"));
        User userB = userRepository.save(new User("b@test.com", "B", "hash"));

        Warehouse template = Warehouse.create(
                "sample",
                20,
                10,
                owner,
                "Seoul",
                "template",
                Warehouse.WarehouseStatus.ACTIVE
        );
        template.markShared();
        template = warehouseRepository.save(template);

        warehouseZoneRepository.save(WarehouseZone.create(
                template,
                "storage",
                WarehouseZone.ZoneType.STORAGE,
                "storage zone",
                0.0,
                10.0,
                0.0,
                10.0
        ));

        WarehouseNode route = warehouseNodeRepository.save(node(
                template, "ROUTE-1", NodeType.ROUTE, 1.0, 1.0
        ));
        WarehouseNode rack = warehouseNodeRepository.save(node(
                template, "RACK-1", NodeType.RACK_STORAGE, 2.0, 1.0
        ));
        WarehouseNode charging = warehouseNodeRepository.save(node(
                template, "CHARGE-1", NodeType.CHARGING_SLOT, 0.0, 0.0
        ));

        warehouseEdgeRepository.save(WarehouseEdge.create(
                route,
                rack,
                1.0,
                WarehouseEdge.DirectionType.BOTH,
                "E-ROUTE-RACK",
                WarehouseEdge.RouteProperties.empty(),
                Map.of("custom", "rack")
        ));
        warehouseEdgeRepository.save(WarehouseEdge.create(
                route,
                charging,
                1.5,
                WarehouseEdge.DirectionType.BOTH,
                "E-ROUTE-CHARGE",
                WarehouseEdge.RouteProperties.empty(),
                Map.of("custom", "charging")
        ));

        chargingStationRepository.save(ChargingStation.create(
                template,
                charging,
                "charger",
                ChargingStation.ChargingStationStatus.AVAILABLE,
                10.0
        ));

        StorageLocation storage = new StorageLocation();
        storage.setWarehouse(template);
        storage.setNode(rack);
        storage.setMaxQuantity(100);
        storage.setMaxWeight(1000);
        storage.setMaxVolume(1000);
        storage.setCreatedAt(LocalDateTime.now());
        storage.setStatus("AVAILABLE");
        storage = storageLocationRepository.save(storage);

        Product product = productRepository.save(Product.create(
                "ITEM-001", "item", "general", "EA", 100,
                "880000000001", "AMBIENT", false
        ));
        WarehouseItem item = WarehouseItem.create(
                template,
                storage,
                1,
                rack,
                product,
                LocalDateTime.now(),
                10
        );
        item.increaseQuantity(5);
        item.decreaseQuantity(3);
        warehouseItemRepository.save(item);

        RobotSpec robotSpec = robotSpecRepository.save(RobotSpec.create(
                "AMR-001", "MOVE", 0.1, 0.5, 0.01
        ));
        robotRepository.save(Robot.create(
                robotSpec,
                template,
                charging.getId(),
                90,
                RobotAvailabilityStatus.AVAILABLE
        ));

        Scenario scenario = Scenario.create(
                template,
                "S1",
                "standard",
                "scenario description",
                1,
                85,
                1.5,
                20,
                true,
                false
        );
        scenario.updateTimings(3.0, 4.0, 6.0);
        scenarioRepository.save(scenario);

        return new Fixture(
                template.getId(),
                userA.getId(),
                userB.getId(),
                product.getId(),
                robotSpec.getId()
        );
    }

    private WarehouseNode node(
            Warehouse warehouse,
            String code,
            NodeType type,
            double x,
            double y
    ) {
        return WarehouseNode.create(
                warehouse,
                "storage",
                x,
                y,
                code,
                type,
                WarehouseNode.RouteProperties.empty(),
                Map.of("custom", code)
        );
    }

    private void assertError(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected)
                );
    }

    private <T> T inTransaction(Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(status -> operation.get());
    }

    private record Fixture(
            Long templateId,
            Long userAId,
            Long userBId,
            Long productId,
            Long robotSpecId
    ) {
    }
}
