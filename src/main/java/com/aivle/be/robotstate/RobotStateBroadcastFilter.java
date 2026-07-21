package com.aivle.be.robotstate;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RobotStateController / RobotStateService는 팀원 코드라 직접 수정하지 않고,
 * PUT /api/robots/{robotId}/state 응답을 필터에서 가로채 그대로 /topic/robots로 브로드캐스트한다.
 * (Task/Event/Simulation처럼 서비스 안에서 SimpMessagingTemplate.convertAndSend 하는 대신,
 *  요청/응답 경로만 관찰해서 붙이는 방식)
 */
@Component
@RequiredArgsConstructor
public class RobotStateBroadcastFilter extends OncePerRequestFilter {

    private static final String TOPIC = "/topic/robots";
    private static final String PATTERN = "/api/robots/*/state";

    private final SimpMessagingTemplate messagingTemplate;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        boolean isStateUpdateRequest = HttpMethod.PUT.matches(request.getMethod())
                && pathMatcher.match(PATTERN, request.getRequestURI());

        if (!isStateUpdateRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrappedResponse);

        if (wrappedResponse.getStatus() == HttpServletResponse.SC_OK) {
            String json = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            if (!json.isBlank()) {
                messagingTemplate.convertAndSend(TOPIC, json);
            }
        }

        // 캐싱된 응답 바디를 실제 응답으로 반드시 복사해줘야 클라이언트가 정상 응답을 받음
        wrappedResponse.copyBodyToResponse();
    }
}
