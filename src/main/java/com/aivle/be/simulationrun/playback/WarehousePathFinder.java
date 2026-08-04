package com.aivle.be.simulationrun.playback;

import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import com.aivle.be.warehouseedge.repository.WarehouseEdgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 창고 그래프 위의 최단 경로 탐색 (BFS).
 *
 * 지금은 백엔드가 직접 경로를 계산하지만,
 * cuOpt 연동이 완료되면 AI가 계산한 경로로 대체된다.
 */
@Component
@RequiredArgsConstructor
public class WarehousePathFinder {

    private final WarehouseEdgeRepository warehouseEdgeRepository;

    /**
     * 창고의 인접 리스트를 만든다.
     * 방향 제약(A_TO_B / B_TO_A)을 반영한다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Set<Long>> loadAdjacency(Long warehouseId) {
        List<WarehouseEdge> edges =
                warehouseEdgeRepository.findAllActiveByWarehouseId(warehouseId);

        Map<Long, Set<Long>> adjacency = new HashMap<>();

        for (WarehouseEdge edge : edges) {
            Long from = edge.getFromNode().getId();
            Long to = edge.getToNode().getId();

            switch (edge.getDirectionType()) {
                case BOTH -> {
                    adjacency.computeIfAbsent(from, key -> new HashSet<>()).add(to);
                    adjacency.computeIfAbsent(to, key -> new HashSet<>()).add(from);
                }
                case A_TO_B ->
                        adjacency.computeIfAbsent(from, key -> new HashSet<>()).add(to);
                case B_TO_A ->
                        adjacency.computeIfAbsent(to, key -> new HashSet<>()).add(from);
            }
        }

        return adjacency;
    }

    /**
     * 최단 경로를 찾는다.
     *
     * @return 출발 노드를 제외한 이동 경로. 경로가 없으면 빈 리스트.
     */
    public List<Long> findPath(Map<Long, Set<Long>> adjacency, Long fromNodeId, Long toNodeId) {
        if (fromNodeId == null || toNodeId == null) {
            return Collections.emptyList();
        }
        if (fromNodeId.equals(toNodeId)) {
            return Collections.emptyList();
        }

        Map<Long, Long> previous = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();

        queue.add(fromNodeId);
        visited.add(fromNodeId);

        while (!queue.isEmpty()) {
            Long current = queue.poll();

            for (Long next : adjacency.getOrDefault(current, Set.of())) {
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                previous.put(next, current);

                if (next.equals(toNodeId)) {
                    return buildPath(previous, fromNodeId, toNodeId);
                }
                queue.add(next);
            }
        }

        return Collections.emptyList();
    }

    private List<Long> buildPath(Map<Long, Long> previous, Long from, Long to) {
        List<Long> path = new ArrayList<>();

        Long cursor = to;
        while (cursor != null && !cursor.equals(from)) {
            path.add(cursor);
            cursor = previous.get(cursor);
        }

        Collections.reverse(path);
        return path;
    }
}
