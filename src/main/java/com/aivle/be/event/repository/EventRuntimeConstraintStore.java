package com.aivle.be.event.repository;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.warehouseedge.entity.WarehouseEdge;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Simulation-run scoped map constraints consumed by LARO from shared Redis. */
@Repository
@RequiredArgsConstructor
public class EventRuntimeConstraintStore {

    private static final String RUN_PREFIX = "simulation:run:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void blockEdges(Long runId, Long eventId, List<WarehouseEdge> edges) {
        try {
            redisTemplate.opsForSet().add(blockingEventIdsKey(runId), eventId.toString());
            for (WarehouseEdge edge : edges) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("edgeId", edge.getId());
                if (edge.getEdgeCode() != null && !edge.getEdgeCode().isBlank()) {
                    state.put("edgeCode", edge.getEdgeCode());
                }
                state.put("status", "BLOCKED");
                redisTemplate.opsForSet().add(edgeIdsKey(runId), edge.getId().toString());
                redisTemplate.opsForSet().add(eventEdgesKey(runId, eventId), edge.getId().toString());
                redisTemplate.opsForSet().add(edgeEventsKey(runId, edge.getId()), eventId.toString());
                redisTemplate.opsForValue().set(
                        edgeStateKey(runId, edge.getId()),
                        serialize(state)
                );
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void releaseEvent(Long runId, Long eventId) {
        try {
            Set<String> edgeIds = redisTemplate.opsForSet()
                    .members(eventEdgesKey(runId, eventId));
            if (edgeIds == null) {
                return;
            }
            for (String edgeId : edgeIds) {
                String referencesKey = edgeEventsKey(runId, Long.valueOf(edgeId));
                redisTemplate.opsForSet().remove(referencesKey, eventId.toString());
                Long remaining = redisTemplate.opsForSet().size(referencesKey);
                if (remaining == null || remaining == 0) {
                    redisTemplate.delete(referencesKey);
                    redisTemplate.opsForSet().remove(edgeIdsKey(runId), edgeId);
                    redisTemplate.delete(edgeStateKey(runId, Long.valueOf(edgeId)));
                }
            }
            redisTemplate.delete(eventEdgesKey(runId, eventId));
            redisTemplate.opsForSet().remove(blockingEventIdsKey(runId), eventId.toString());
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize runtime edge constraint", exception);
        }
    }

    private String edgeIdsKey(Long runId) {
        return RUN_PREFIX + runId + ":edges";
    }

    private String edgeStateKey(Long runId, Long edgeId) {
        return RUN_PREFIX + runId + ":edge:" + edgeId + ":state";
    }

    private String eventEdgesKey(Long runId, Long eventId) {
        return RUN_PREFIX + runId + ":event:" + eventId + ":blocked-edges";
    }

    private String edgeEventsKey(Long runId, Long edgeId) {
        return RUN_PREFIX + runId + ":edge:" + edgeId + ":blocking-events";
    }

    private String blockingEventIdsKey(Long runId) {
        return RUN_PREFIX + runId + ":blocking-events";
    }

    private BusinessException unavailable(DataAccessException exception) {
        return new BusinessException(ErrorCode.ROBOT_STATE_STORE_UNAVAILABLE, exception);
    }
}
