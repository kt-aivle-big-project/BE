package com.aivle.be.task.generation;

import com.aivle.be.product.entity.Product;
import com.aivle.be.product.repository.ProductRepository;
import com.aivle.be.simulationrun.controller.request.InboundConfigRequest;
import com.aivle.be.simulationrun.controller.request.OutboundConfigRequest;
import com.aivle.be.simulationrun.domain.ArrivalPattern;
import com.aivle.be.storagelocation.entity.StorageLocation;
import com.aivle.be.storagelocation.repository.StorageLocationRepository;
import com.aivle.be.task.entity.TaskType;
import com.aivle.be.task.service.TaskCreateCommand;
import com.aivle.be.task.service.TaskCreationService;
import com.aivle.be.warehouseitem.entity.WarehouseItem;
import com.aivle.be.warehouseitem.repository.WarehouseItemRepository;
import com.aivle.be.warehousenode.domain.NodeType;
import com.aivle.be.warehousenode.entity.WarehouseNode;
import com.aivle.be.warehousenode.repository.WarehouseNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 시나리오 설정(입고/출고 건수·수량·발생 패턴)을 실제 작업 목록으로 펼친다.
 *
 * 시뮬레이션 생성 시점에 전체 작업을 한 번에 만들어 둔다.
 * 각 작업에는 "시뮬레이션 시작 후 몇 초에 발생하는지"(releaseAtSeconds)가 붙는다.
 *
 * 재생 엔진은 이 시각이 되어야 작업을 투입하고,
 * cuOpt 연동 시에는 이 전체 목록을 한 번에 넘겨 최적 배정을 받는다.
 */
@Service
@RequiredArgsConstructor
public class ScenarioTaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioTaskPlanner.class);

    // 작업 1건당 타임라인 간격(초).
    // 작업이 5건이면 0~75초에 걸쳐 발생한다.
    private static final int SECONDS_PER_TASK = 15;

    // 전체 발생 구간 상한(초).
    // 건수가 많아도 이 시간 안에 모두 발생시킨다.
    // (없으면 출고 30건 = 450초라 시연 중에 끝나지 않는다)
    private static final int MAX_HORIZON_SECONDS = 180;

    // 한 번의 실행에서 만들 수 있는 최대 작업 수.
    // 실수로 큰 값을 넣었을 때 DB가 폭주하는 것을 막는다.
    private static final int MAX_TASKS_PER_TYPE = 100;

    private final WarehouseNodeRepository warehouseNodeRepository;
    private final WarehouseItemRepository warehouseItemRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ProductRepository productRepository;
    private final TaskCreationService taskCreationService;

    /**
     * 입고/출고 설정을 작업으로 펼쳐 저장한다.
     *
     * @return 생성된 작업 건수
     */
    @Transactional
    public int plan(
            Long simulationRunId,
            Long warehouseId,
            InboundConfigRequest inbound,
            OutboundConfigRequest outbound,
            Long seed
    ) {
        Random random = seed == null ? new Random() : new Random(seed);

        List<TaskCreateCommand> commands = new ArrayList<>();
        commands.addAll(planInbound(simulationRunId, warehouseId, inbound, random));
        commands.addAll(planOutbound(simulationRunId, warehouseId, outbound, random));

        if (commands.isEmpty()) {
            log.info("[시나리오] runId={} 생성할 작업이 없습니다. (입고/출고 설정 없음)", simulationRunId);
            return 0;
        }

        for (TaskCreateCommand command : commands) {
            try {
                taskCreationService.create(command);
            } catch (Exception exception) {
                log.warn("[시나리오] 작업 생성 실패 - {}", exception.getMessage());
            }
        }

        log.info("[시나리오] runId={} 작업 {}건 생성 완료", simulationRunId, commands.size());
        return commands.size();
    }

    /* =========================================================
       입고 작업
    ========================================================= */

    /**
     * 입고구역 노드에서 출발해 보관 랙에 적재하는 작업을 만든다.
     * 품목은 설정된 비율대로 배분한다.
     */
    private List<TaskCreateCommand> planInbound(
            Long simulationRunId,
            Long warehouseId,
            InboundConfigRequest inbound,
            Random random
    ) {
        if (inbound == null || inbound.inboundCount() == null || inbound.inboundCount() <= 0) {
            return List.of();
        }

        List<WarehouseNode> inboundNodes = warehouseNodeRepository
                .findAllByWarehouse_IdAndNodeType(warehouseId, NodeType.INBOUND);

        // 적재 대상은 보관위치가 등록된 노드만 가능하다.
        // (보관위치가 없으면 작업 완료 시 재고 반영에서 실패한다)
        List<StorageLocation> locations = storageLocationRepository
                .findAllByWarehouse_Id(warehouseId);

        if (inboundNodes.isEmpty() || locations.isEmpty()) {
            log.warn("[시나리오] 입고 작업 생성 불가 - 입고구역 {}개, 보관위치 {}개",
                    inboundNodes.size(), locations.size());
            return List.of();
        }

        int count = capCount(inbound.inboundCount(), "입고");
        int quantityPerTask = dividePositive(inbound.totalQuantity(), count);
        List<Long> itemIds = distributeItemsByRatio(inbound, count);
        List<Integer> releaseTimes = spreadReleaseTimes(count, inbound.arrivalPattern(), random);

        List<TaskCreateCommand> commands = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            WarehouseNode startNode = pick(inboundNodes, random);
            StorageLocation targetLocation = pick(locations, random);

            commands.add(new TaskCreateCommand(
                    warehouseId,
                    startNode.getId(),
                    targetLocation.getNode().getId(),
                    null,
                    itemIds.get(index),
                    TaskType.INBOUND,
                    simulationRunId,
                    quantityPerTask,
                    releaseTimes.get(index)
            ));
        }

        return commands;
    }

    /* =========================================================
       출고 작업
    ========================================================= */

    /**
     * 재고가 있는 랙에서 출발해 출고구역으로 옮기는 작업을 만든다.
     *
     * 같은 랙에서 여러 건이 나갈 수 있으므로 남은 수량을 추적한다.
     * 재고가 모자라면 만들 수 있는 만큼만 만든다.
     */
    private List<TaskCreateCommand> planOutbound(
            Long simulationRunId,
            Long warehouseId,
            OutboundConfigRequest outbound,
            Random random
    ) {
        if (outbound == null || outbound.orderCount() == null || outbound.orderCount() <= 0) {
            return List.of();
        }

        List<WarehouseNode> outboundNodes = warehouseNodeRepository
                .findAllByWarehouse_IdAndNodeType(warehouseId, NodeType.OUTBOUND);

        List<WarehouseItem> stocked = warehouseItemRepository
                .findAllByWarehouse_Id(warehouseId)
                .stream()
                .filter(item -> item.getQuantity() != null && item.getQuantity() > 0)
                .filter(item -> item.getNode() != null)
                .toList();

        if (outboundNodes.isEmpty() || stocked.isEmpty()) {
            log.warn("[시나리오] 출고 작업 생성 불가 - 출고구역 {}개, 재고 보유 랙 {}개",
                    outboundNodes.size(), stocked.size());
            return List.of();
        }

        int count = capCount(outbound.orderCount(), "출고");
        int quantityPerTask = dividePositive(outbound.totalQuantity(), count);
        List<Integer> releaseTimes = spreadReleaseTimes(count, outbound.arrivalPattern(), random);

        // 랙별 남은 재고 (생성 단계에서만 쓰는 가상 잔량)
        Map<Long, Integer> remaining = new HashMap<>();
        for (WarehouseItem item : stocked) {
            remaining.put(item.getId(), item.getQuantity());
        }

        List<TaskCreateCommand> commands = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            WarehouseItem source = pickAvailable(stocked, remaining, quantityPerTask, random);

            if (source == null) {
                log.info("[시나리오] 재고 부족으로 출고 작업 {}건만 생성했습니다. (요청 {}건)",
                        commands.size(), count);
                break;
            }

            remaining.merge(source.getId(), -quantityPerTask, Integer::sum);

            commands.add(new TaskCreateCommand(
                    warehouseId,
                    source.getNode().getId(),
                    pick(outboundNodes, random).getId(),
                    source.getId(),
                    source.getItemId(),
                    TaskType.OUTBOUND,
                    simulationRunId,
                    quantityPerTask,
                    releaseTimes.get(index)
            ));
        }

        return commands;
    }

    /**
     * 요청 수량만큼 남아 있는 랙을 고른다. 없으면 null.
     */
    private WarehouseItem pickAvailable(
            List<WarehouseItem> stocked,
            Map<Long, Integer> remaining,
            int required,
            Random random
    ) {
        List<WarehouseItem> candidates = stocked.stream()
                .filter(item -> remaining.getOrDefault(item.getId(), 0) >= required)
                .toList();

        return candidates.isEmpty() ? null : pick(candidates, random);
    }

    /* =========================================================
       발생 시각 분배
    ========================================================= */

    /**
     * 작업 발생 시각을 패턴에 따라 분배한다.
     *
     * UNIFORM  균등 간격
     * RANDOM   전체 구간에 무작위
     * PEAK     앞쪽에 몰림 (러시아워)
     */
    private List<Integer> spreadReleaseTimes(int count, ArrivalPattern pattern, Random random) {
        // 건수가 많아도 MAX_HORIZON_SECONDS 안에 모두 발생하도록 압축한다
        int horizon = Math.max(1, Math.min(count * SECONDS_PER_TASK, MAX_HORIZON_SECONDS));

        // 균등 간격도 압축된 구간에 맞춰 다시 계산한다
        double interval = count <= 1 ? 0 : (double) horizon / count;

        List<Integer> times = new ArrayList<>(count);

        ArrivalPattern effective = pattern == null ? ArrivalPattern.UNIFORM : pattern;

        for (int index = 0; index < count; index++) {
            int seconds = switch (effective) {
                case UNIFORM -> (int) Math.round(index * interval);
                case RANDOM -> random.nextInt(horizon);
                // 제곱하면 0에 가까운 값이 많이 나와 앞쪽으로 몰린다
                case PEAK -> (int) (horizon * Math.pow(random.nextDouble(), 2));
            };
            times.add(Math.max(0, seconds));
        }

        times.sort(Integer::compareTo);
        return times;
    }

    /* =========================================================
       유틸
    ========================================================= */

    /**
     * 품목 구성 비율대로 작업별 품목을 배분한다.
     * 예) A 50%, B 50%, 10건 -> A 5건, B 5건
     */
    private List<Long> distributeItemsByRatio(InboundConfigRequest inbound, int count) {
        List<Long> itemIds = new ArrayList<>(count);

        if (inbound.products() != null) {
            for (InboundConfigRequest.ProductRatio ratio : inbound.products()) {
                Long productId = findProductId(ratio.productCode());

                if (productId == null) {
                    continue;
                }

                int share = Math.round(count * (ratio.ratio() == null ? 0 : ratio.ratio()) / 100f);

                for (int index = 0; index < share && itemIds.size() < count; index++) {
                    itemIds.add(productId);
                }
            }
        }

        // 반올림 오차나 품목 미지정으로 모자란 만큼 채운다
        Long fallback = itemIds.isEmpty() ? firstProductId() : itemIds.get(0);

        while (itemIds.size() < count) {
            itemIds.add(fallback);
        }

        return itemIds;
    }

    private Long findProductId(String productCode) {
        if (productCode == null) {
            return null;
        }
        return productRepository.findByProductCode(productCode)
                .map(Product::getId)
                .orElse(null);
    }

    private Long firstProductId() {
        return productRepository.findAllByOrderByProductCodeAsc()
                .stream()
                .findFirst()
                .map(Product::getId)
                .orElse(null);
    }

    /**
     * 작업 건수를 상한선으로 자른다.
     */
    private int capCount(int requested, String label) {
        if (requested > MAX_TASKS_PER_TYPE) {
            log.warn("[시나리오] {} 요청 {}건은 상한({}건)을 넘어 잘렸습니다.",
                    label, requested, MAX_TASKS_PER_TYPE);
            return MAX_TASKS_PER_TYPE;
        }
        return requested;
    }

    private int dividePositive(Integer total, int count) {
        if (total == null || total <= 0 || count <= 0) {
            return 1;
        }
        return Math.max(1, total / count);
    }

    private <T> T pick(List<T> candidates, Random random) {
        return candidates.get(random.nextInt(candidates.size()));
    }
}
