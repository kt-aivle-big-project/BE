package com.aivle.be.boardpost.controller;

import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.jwt.JwtAccessDeniedHandler;
import com.aivle.be.auth.jwt.JwtAuthenticationEntryPoint;
import com.aivle.be.auth.jwt.JwtAuthenticationFilter;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.boardpost.service.BoardPostService;
import com.aivle.be.global.config.SecurityConfig;
import com.aivle.be.global.exception.GlobalExceptionHandler;
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

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BoardPostController.class)
@Import({
        SecurityConfig.class,
        AuthenticatedRequesterResolver.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        GlobalExceptionHandler.class
})
class BoardPostControllerWebMvcTest {

    private static final String GUEST_SESSION_ID =
            "a4d70ea4-9a96-4c75-8414-24a43114a962";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BoardPostService boardPostService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void userAndGuestCanReadBoardPosts() throws Exception {
        mockMvc.perform(get("/api/board-posts").with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/board-posts/1").with(user()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/board-posts").with(guest()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/board-posts/1").with(guest()))
                .andExpect(status().isOk());
    }

    @Test
    void guestCannotCreateUpdateOrDeleteBoardPost() throws Exception {
        String body = "{\"title\":\"제목\",\"content\":\"내용\"}";

        mockMvc.perform(post("/api/board-posts")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/board-posts/1")
                        .with(guest())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/board-posts/1").with(guest()))
                .andExpect(status().isForbidden());
    }

    @Test
    void userCanCreateUpdateAndDeleteBoardPost() throws Exception {
        String body = "{\"title\":\"제목\",\"content\":\"내용\"}";

        mockMvc.perform(post("/api/board-posts")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/api/board-posts/1")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/board-posts/1").with(user()))
                .andExpect(status().isNoContent());
    }

    @Test
    void blankTitleOrContentReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/board-posts")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"content\":\"내용\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
        mockMvc.perform(patch("/api/board-posts/1")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"제목\",\"content\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    @Test
    void overlongTitleReturnsBadRequest() throws Exception {
        String body = "{\"title\":\"" + "가".repeat(201)
                + "\",\"content\":\"내용\"}";

        mockMvc.perform(post("/api/board-posts")
                        .with(user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
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
}
