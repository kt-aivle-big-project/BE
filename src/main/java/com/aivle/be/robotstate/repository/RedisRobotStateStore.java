package com.aivle.be.robotstate.repository;

import com.aivle.be.robotstate.domain.RobotState;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class RedisRobotStateStore implements RobotStateStore {

    private static final String ROBOT_STATE_KEY_PREFIX = "robot:state:";
    private static final String WAREHOUSE_ROBOTS_KEY_PREFIX = "warehouse:robots:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public RobotState save(RobotState state) {
        String stateKey = robotStateKey(state.robotId());
        String warehouseKey = warehouseRobotsKey(state.warehouseId());

        redisTemplate.opsForValue().set(stateKey, serialize(state));
        redisTemplate.opsForSet().add(warehouseKey, state.robotId().toString());
        return state;
    }

    @Override
    public Optional<RobotState> findByRobotId(Long robotId) {
        String json = redisTemplate.opsForValue().get(robotStateKey(robotId));
        return Optional.ofNullable(json).map(this::deserialize);
    }

    @Override
    public List<RobotState> findAllByWarehouseId(Long warehouseId) {
        Set<String> robotIds = redisTemplate.opsForSet().members(warehouseRobotsKey(warehouseId));
        if (robotIds == null || robotIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> keys = robotIds.stream()
                .map(Long::valueOf)
                .sorted()
                .map(this::robotStateKey)
                .toList();

        List<String> states = redisTemplate.opsForValue().multiGet(keys);
        if (states == null) {
            return Collections.emptyList();
        }

        return states.stream()
                .filter(json -> json != null)
                .map(this::deserialize)
                .toList();
    }

    private String serialize(RobotState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JacksonException exception) {
            throw new IllegalStateException("로봇 상태를 JSON으로 변환할 수 없습니다.", exception);
        }
    }

    private RobotState deserialize(String json) {
        try {
            return objectMapper.readValue(json, RobotState.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Redis의 로봇 상태를 읽을 수 없습니다.", exception);
        }
    }

    private String robotStateKey(Long robotId) {
        return ROBOT_STATE_KEY_PREFIX + robotId;
    }

    private String warehouseRobotsKey(Long warehouseId) {
        return WAREHOUSE_ROBOTS_KEY_PREFIX + warehouseId;
    }
}
