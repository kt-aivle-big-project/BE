package com.aivle.be.simulationrun.repository;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robotstate.domain.RobotState;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RedisSimulationRunStateStore implements SimulationRunStateStore {

    private static final String RUN_PREFIX = "simulation:run:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public RobotState save(Long simulationRunId, RobotState state) {
        try {
            redisTemplate.opsForValue().set(robotStateKey(simulationRunId, state.robotId()), serialize(state));
            redisTemplate.opsForSet().add(robotIdsKey(simulationRunId), state.robotId().toString());
            return state;
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public Optional<RobotState> findByRobotId(Long simulationRunId, Long robotId) {
        try {
            String value = redisTemplate.opsForValue().get(robotStateKey(simulationRunId, robotId));
            return Optional.ofNullable(value).map(this::deserialize);
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public List<RobotState> findAll(Long simulationRunId) {
        try {
            Set<String> robotIds = redisTemplate.opsForSet().members(robotIdsKey(simulationRunId));
            if (robotIds == null || robotIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> keys = robotIds.stream()
                    .map(Long::valueOf)
                    .sorted()
                    .map(robotId -> robotStateKey(simulationRunId, robotId))
                    .toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return Collections.emptyList();
            }

            return values.stream()
                    .filter(value -> value != null)
                    .map(this::deserialize)
                    .toList();
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public void deleteAll(Long simulationRunId) {
        try {
            Set<String> robotIds = redisTemplate.opsForSet().members(robotIdsKey(simulationRunId));
            if (robotIds != null && !robotIds.isEmpty()) {
                List<String> stateKeys = robotIds.stream()
                        .map(Long::valueOf)
                        .sorted()
                        .map(robotId -> robotStateKey(simulationRunId, robotId))
                        .toList();
                redisTemplate.delete(stateKeys);
            }
            redisTemplate.delete(robotIdsKey(simulationRunId));

            Set<String> edgeIds = redisTemplate.opsForSet().members(edgeIdsKey(simulationRunId));
            if (edgeIds != null) {
                for (String edgeId : edgeIds) {
                    redisTemplate.delete(edgeStateKey(simulationRunId, edgeId));
                    redisTemplate.delete(edgeEventsKey(simulationRunId, edgeId));
                }
            }
            Set<String> eventIds = redisTemplate.opsForSet()
                    .members(blockingEventIdsKey(simulationRunId));
            if (eventIds != null) {
                for (String eventId : eventIds) {
                    redisTemplate.delete(eventEdgesKey(simulationRunId, eventId));
                }
            }
            redisTemplate.delete(edgeIdsKey(simulationRunId));
            redisTemplate.delete(blockingEventIdsKey(simulationRunId));
        } catch (DataAccessException exception) {
            throw storeUnavailable(exception);
        }
    }

    private String serialize(RobotState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JacksonException exception) {
            throw new BusinessException(ErrorCode.ROBOT_STATE_DATA_CORRUPTED, exception);
        }
    }

    private RobotState deserialize(String value) {
        try {
            return objectMapper.readValue(value, RobotState.class);
        } catch (JacksonException exception) {
            throw new BusinessException(ErrorCode.ROBOT_STATE_DATA_CORRUPTED, exception);
        }
    }

    private String robotStateKey(Long simulationRunId, Long robotId) {
        return RUN_PREFIX + simulationRunId + ":robot:" + robotId + ":state";
    }

    private String robotIdsKey(Long simulationRunId) {
        return RUN_PREFIX + simulationRunId + ":robots";
    }

    private String edgeIdsKey(Long runId) {
        return RUN_PREFIX + runId + ":edges";
    }

    private String edgeStateKey(Long runId, String edgeId) {
        return RUN_PREFIX + runId + ":edge:" + edgeId + ":state";
    }

    private String edgeEventsKey(Long runId, String edgeId) {
        return RUN_PREFIX + runId + ":edge:" + edgeId + ":blocking-events";
    }

    private String blockingEventIdsKey(Long runId) {
        return RUN_PREFIX + runId + ":blocking-events";
    }

    private String eventEdgesKey(Long runId, String eventId) {
        return RUN_PREFIX + runId + ":event:" + eventId + ":blocked-edges";
    }

    private BusinessException storeUnavailable(DataAccessException exception) {
        return new BusinessException(ErrorCode.ROBOT_STATE_STORE_UNAVAILABLE, exception);
    }
}
