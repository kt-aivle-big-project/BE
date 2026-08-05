package com.aivle.be.warehouse.controller;

import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.jwt.JwtAccessDeniedHandler;
import com.aivle.be.auth.jwt.JwtAuthenticationEntryPoint;
import com.aivle.be.auth.jwt.JwtAuthenticationFilter;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.config.SecurityConfig;
import com.aivle.be.global.exception.GlobalExceptionHandler;
import com.aivle.be.scenario.controller.ScenarioController;
import com.aivle.be.scenario.service.ScenarioService;
import com.aivle.be.simulationrun.controller.SimulationRunController;
import com.aivle.be.simulationrun.service.SimulationRunService;
import com.aivle.be.task.service.TaskService;
import com.aivle.be.warehouse.service.WarehouseGraphService;
import com.aivle.be.warehouse.service.WarehouseImportService;
import com.aivle.be.warehouse.service.WarehouseLayoutService;
import com.aivle.be.warehouse.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        WarehouseController.class,
        ScenarioController.class,
        SimulationRunController.class,
        GuestResourceAccessWebMvcTest.SecurityProbeController.class
})
@Import({
        SecurityConfig.class,
        AuthenticatedRequesterResolver.class,
        GuestAccessPolicy.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        GuestResourceAccessWebMvcTest.SecurityProbeController.class
})
class GuestResourceAccessWebMvcTest {

    private static final String GUEST_SESSION_ID =
            "a4d70ea4-9a96-4c75-8414-24a43114a962";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WarehouseService warehouseService;
    @MockitoBean
    private WarehouseLayoutService warehouseLayoutService;
    @MockitoBean
    private WarehouseGraphService warehouseGraphService;
    @MockitoBean
    private WarehouseImportService warehouseImportService;
    @MockitoBean
    private ScenarioService scenarioService;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private SimulationRunService simulationRunService;
    @MockitoBean
    private TaskService taskService;

    @Test
    void guestCanReadOnlyDemoWarehouseResourcesAndScenario() throws Exception {
        mockMvc.perform(get("/api/warehouses/1").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/warehouses/1/graph").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/warehouses/1/layout").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios/101").with(guest()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/warehouses/2").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/warehouses/2/graph").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/warehouses/2/layout").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/scenarios/2").with(guest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCannotListOrMutateWarehouseAndScenario() throws Exception {
        mockMvc.perform(get("/api/warehouses").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/warehouses")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/warehouses/1")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/warehouses/1").with(guest()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/scenarios").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/scenarios")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/scenarios/101")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/scenarios/101").with(guest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCanAccessOnlyAllowlistedSimulationRunOperations() throws Exception {
        mockMvc.perform(post("/api/simulation-runs")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "warehouseId": 1,
                                  "scenarioId": 1
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/simulation-runs/my").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation-runs/10/start").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation-runs/10/pause").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation-runs/10/resume").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/simulation-runs/10/speed")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simulationSpeed\": 2.0}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation-runs/10/reset").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/simulation-runs/10/stop").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/simulation-runs/10/status").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/simulation-runs/10/robots").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/simulation-runs/10/tasks").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/simulation-runs/10/robots/states").with(guest()))
                .andExpect(status().isOk());
    }

    @Test
    void guestCannotAccessRepresentativeNonAllowlistedApis() throws Exception {
        List<String> paths = List.of(
                "/api/simulations",
                "/api/tasks",
                "/api/events",
                "/api/charging-stations",
                "/api/storage-locations",
                "/api/robots",
                "/api/robot-specs",
                "/api/warehouse-nodes",
                "/api/warehouse-edges",
                "/api/warehouse-zones",
                "/api/warehouse-items",
                "/api/optimizations"
        );

        for (String path : paths) {
            mockMvc.perform(get(path).with(guest()))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(post("/api/simulations").with(guest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCannotAccessUnlistedFutureApiByDefault() throws Exception {
        mockMvc.perform(get("/api/not-explicitly-allowlisted").with(guest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestCannotCallInternalSimulationOperations() throws Exception {
        mockMvc.perform(post("/api/simulation-runs/stop-active")
                        .param("warehouseId", "1")
                        .with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/simulation-runs/10/complete").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/simulation-runs/10/fail").with(guest()))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/simulation-runs/10/robots/5/state")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void userKeepsExistingWarehouseAndScenarioReadAccess() throws Exception {
        mockMvc.perform(get("/api/warehouses/2").with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios/2").with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tasks").with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/not-explicitly-allowlisted").with(user()))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor guest() {
        return authentication(new UsernamePasswordAuthenticationToken(
                GUEST_SESSION_ID,
                null,
                List.of(new SimpleGrantedAuthority(AuthRole.GUEST.authority()))
        ));
    }

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(
                "7",
                null,
                List.of(new SimpleGrantedAuthority(AuthRole.USER.authority()))
        ));
    }

    @RestController
    public static class SecurityProbeController {

        @GetMapping({
                "/api/simulations",
                "/api/tasks",
                "/api/events",
                "/api/charging-stations",
                "/api/storage-locations",
                "/api/not-explicitly-allowlisted"
        })
        void getProbe() {
        }

        @PostMapping("/api/simulations")
        void postProbe() {
        }
    }
}
