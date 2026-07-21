package com.aivle.be.robotstate.repository;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.robotstate.domain.RobotState;
import com.aivle.be.robotstate.domain.RobotStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class RedisRobotStateStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private RedisRobotStateStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        store = new RedisRobotStateStore(redisTemplate, objectMapper);
    }

    @Test
    void 로봇_상태와_창고별_로봇_인덱스를_저장한다() throws JacksonException {
        RobotState state = state(1L, 1L, 10L, 90, RobotStatus.MOVING);
        String json = "{\"robotId\":1}";
        when(objectMapper.writeValueAsString(state)).thenReturn(json);

        RobotState saved = store.save(state);

        assertThat(saved).isSameAs(state);
        verify(valueOperations).set("robot:state:1", json);
        verify(setOperations).add("warehouse:robots:1", "1");
    }

    @Test
    void 로봇_ID로_상태를_조회한다() throws JacksonException {
        RobotState state = state(1L, 1L, 10L, 90, RobotStatus.IDLE);
        String json = "{\"robotId\":1}";
        when(valueOperations.get("robot:state:1")).thenReturn(json);
        when(objectMapper.readValue(json, RobotState.class)).thenReturn(state);

        Optional<RobotState> result = store.findByRobotId(1L);

        assertThat(result).contains(state);
    }

    @Test
    void 존재하지_않는_로봇은_빈_결과를_반환한다() {
        when(valueOperations.get("robot:state:999")).thenReturn(null);

        Optional<RobotState> result = store.findByRobotId(999L);

        assertThat(result).isEmpty();
    }

    @Test
    void 창고에_속한_로봇_상태를_ID_순서로_조회한다() throws JacksonException {
        RobotState first = state(1L, 1L, 10L, 90, RobotStatus.IDLE);
        RobotState second = state(2L, 1L, 20L, 80, RobotStatus.MOVING);
        when(setOperations.members("warehouse:robots:1")).thenReturn(Set.of("2", "1"));
        when(valueOperations.multiGet(List.of("robot:state:1", "robot:state:2")))
                .thenReturn(List.of("state-1", "state-2"));
        when(objectMapper.readValue("state-1", RobotState.class)).thenReturn(first);
        when(objectMapper.readValue("state-2", RobotState.class)).thenReturn(second);

        List<RobotState> result = store.findAllByWarehouseId(1L);

        assertThat(result).containsExactly(first, second);
    }

    @Test
    void 창고_인덱스가_없으면_빈_목록을_반환한다() {
        when(setOperations.members("warehouse:robots:999")).thenReturn(Set.of());

        List<RobotState> result = store.findAllByWarehouseId(999L);

        assertThat(result).isEmpty();
        verify(valueOperations, never()).multiGet(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void Redis의_JSON이_손상되면_명확한_예외를_발생시킨다() throws JacksonException {
        String invalidJson = "invalid-json";
        JacksonException cause = org.mockito.Mockito.mock(JacksonException.class);
        when(valueOperations.get("robot:state:1")).thenReturn(invalidJson);
        when(objectMapper.readValue(invalidJson, RobotState.class)).thenThrow(cause);

        assertThatThrownBy(() -> store.findByRobotId(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROBOT_STATE_DATA_CORRUPTED);
                    assertThat(exception).hasCause(cause);
                });
    }

    @Test
    void Redis_저장에_실패하면_저장소_사용불가_예외를_발생시킨다() throws JacksonException {
        RobotState state = state(1L, 1L, 10L, 90, RobotStatus.MOVING);
        when(objectMapper.writeValueAsString(state)).thenReturn("state-json");
        doThrow(new RedisConnectionFailureException("connection failed"))
                .when(valueOperations).set("robot:state:1", "state-json");

        assertThatThrownBy(() -> store.save(state))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROBOT_STATE_STORE_UNAVAILABLE));
    }

    @Test
    void Redis_조회에_실패하면_저장소_사용불가_예외를_발생시킨다() {
        when(valueOperations.get("robot:state:1"))
                .thenThrow(new RedisConnectionFailureException("connection failed"));

        assertThatThrownBy(() -> store.findByRobotId(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROBOT_STATE_STORE_UNAVAILABLE));
    }

    private RobotState state(Long robotId, Long warehouseId, Long nodeId,
                             Integer batteryLevel, RobotStatus status) {
        return new RobotState(
                robotId,
                warehouseId,
                nodeId,
                batteryLevel,
                status,
                null,
                LocalDateTime.of(2026, 7, 21, 10, 0)
        );
    }
}
