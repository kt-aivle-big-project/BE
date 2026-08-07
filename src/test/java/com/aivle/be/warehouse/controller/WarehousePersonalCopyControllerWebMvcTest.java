package com.aivle.be.warehouse.controller;

import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.jwt.JwtAccessDeniedHandler;
import com.aivle.be.auth.jwt.JwtAuthenticationEntryPoint;
import com.aivle.be.auth.jwt.JwtAuthenticationFilter;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.security.GuestAccessPolicy;
import com.aivle.be.global.config.SecurityConfig;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.global.exception.GlobalExceptionHandler;
import com.aivle.be.user.entity.User;
import com.aivle.be.warehouse.entity.Warehouse;
import com.aivle.be.warehouse.service.WarehouseGraphService;
import com.aivle.be.warehouse.service.WarehouseImportService;
import com.aivle.be.warehouse.service.WarehouseLayoutService;
import com.aivle.be.warehouse.service.WarehouseService;
import com.aivle.be.warehouse.service.WarehouseTemplateCloneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WarehouseController.class)
@Import({
        SecurityConfig.class,
        AuthenticatedRequesterResolver.class,
        GuestAccessPolicy.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
class WarehousePersonalCopyControllerWebMvcTest {

    private static final Long TEMPLATE_ID = 10L;
    private static final Long USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WarehouseService warehouseService;
    @MockitoBean private WarehouseLayoutService warehouseLayoutService;
    @MockitoBean private WarehouseGraphService warehouseGraphService;
    @MockitoBean private WarehouseImportService warehouseImportService;
    @MockitoBean private WarehouseTemplateCloneService warehouseTemplateCloneService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;

    private Warehouse personalCopy;

    @BeforeEach
    void setUp() {
        User owner = new User("owner@test.com", "owner", "hash");
        ReflectionTestUtils.setField(owner, "id", 1L);
        Warehouse template = Warehouse.create("template", 20, 10, owner);
        template.markShared();
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);

        User user = new User("user@test.com", "user", "hash");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        personalCopy = Warehouse.createPersonalCopy(template, user);
        ReflectionTestUtils.setField(personalCopy, "id", 20L);
    }

    @Test
    void userCreatesOrGetsSamePersonalCopyWithoutRequestBody() throws Exception {
        when(warehouseTemplateCloneService.ensurePersonalCopy(TEMPLATE_ID, USER_ID))
                .thenReturn(personalCopy);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/warehouses/{templateWarehouseId}/personal-copy", TEMPLATE_ID)
                            .with(user()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(20))
                    .andExpect(jsonPath("$.userId").value(USER_ID))
                    .andExpect(jsonPath("$.sourceTemplateId").value(TEMPLATE_ID))
                    .andExpect(jsonPath("$.shared").value(false));
        }
    }

    @Test
    void guestAndAnonymousCannotCreatePersonalCopy() throws Exception {
        mockMvc.perform(post("/api/warehouses/{templateWarehouseId}/personal-copy", TEMPLATE_ID)
                        .with(guest()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/warehouses/{templateWarehouseId}/personal-copy", TEMPLATE_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonTemplateAndMissingWarehouseErrorsAreReturned() throws Exception {
        when(warehouseTemplateCloneService.ensurePersonalCopy(TEMPLATE_ID, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.WAREHOUSE_NOT_TEMPLATE));
        when(warehouseTemplateCloneService.ensurePersonalCopy(999L, USER_ID))
                .thenThrow(new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));

        mockMvc.perform(post("/api/warehouses/{templateWarehouseId}/personal-copy", TEMPLATE_ID)
                        .with(user()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/warehouses/{templateWarehouseId}/personal-copy", 999L)
                        .with(user()))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(
                USER_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority(AuthRole.USER.authority()))
        ));
    }

    private RequestPostProcessor guest() {
        return authentication(new UsernamePasswordAuthenticationToken(
                "a4d70ea4-9a96-4c75-8414-24a43114a962",
                null,
                List.of(new SimpleGrantedAuthority(AuthRole.GUEST.authority()))
        ));
    }
}
