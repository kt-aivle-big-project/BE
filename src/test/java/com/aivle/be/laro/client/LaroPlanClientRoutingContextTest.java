package com.aivle.be.laro.client;

import com.aivle.be.laro.dto.LaroPlanRequest;
import com.aivle.be.laro.dto.LaroLowBatteryContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LaroPlanClientRoutingContextTest {

    @Test
    @SuppressWarnings("unchecked")
    void serializesRoutingContextWithAiSnakeCaseContract() throws Exception {
        LaroPlanRequest request = new LaroPlanRequest(
                new LaroPlanRequest.StructuredInput(
                        "REQ-ROUTING",
                        List.of(),
                        Map.of("objective_profile", "MIN_COMPLETION_TIME"),
                        new LaroPlanRequest.RoutingContext(10, 4, 3, 6, 1, 2, "TEST")
                ),
                null,
                "cuopt",
                null
        );
        Method method = LaroPlanClient.class.getDeclaredMethod("toAiRequest", LaroPlanRequest.class);
        method.setAccessible(true);

        Map<String, Object> body = (Map<String, Object>) method.invoke(null, request);
        Map<String, Object> structured = (Map<String, Object>) body.get("structured_input");
        Map<String, Object> routing = (Map<String, Object>) structured.get("routing_context");

        assertThat(routing)
                .containsEntry("new_operation_count", 10)
                .containsEntry("unfinished_operation_count", 4)
                .containsEntry("eligible_robot_count", 3)
                .containsEntry("source", "TEST");
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesLowBatterySafeStopStateWithAiSnakeCaseContract() throws Exception {
        LaroLowBatteryContext context = new LaroLowBatteryContext(
                225L, 20, 20, 103L, "A03", 2792L, false, 12_500L
        );
        Method method = LaroPlanClient.class.getDeclaredMethod(
                "toAiLowBatteryContext", LaroLowBatteryContext.class
        );
        method.setAccessible(true);

        Map<String, Object> body = (Map<String, Object>) method.invoke(null, context);

        assertThat(body)
                .containsEntry("status", "LOW_BATTERY")
                .containsEntry("robot_id", "R225")
                .containsEntry("robot_numeric_id", 225L)
                .containsEntry("battery_pct", 20)
                .containsEntry("charging_threshold_pct", 20)
                .containsEntry("current_node", "A03")
                .containsEntry("current_node_numeric_id", 103L)
                .containsEntry("current_task_id", 2792L)
                .containsEntry("carrying_load", false)
                .containsEntry("stopped_at_sim_time_ms", 12_500L);
    }
}
