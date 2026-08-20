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

@Component
@RequiredArgsConstructor
public class WarehousePathFinder {

    private final WarehouseEdgeRepository warehouseEdgeRepository;

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
