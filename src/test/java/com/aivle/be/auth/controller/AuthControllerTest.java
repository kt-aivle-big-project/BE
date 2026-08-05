package com.aivle.be.auth.controller;

import com.aivle.be.auth.dto.response.GuestLoginResponse;
import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.service.AuthService;
import com.aivle.be.auth.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void guestEndpointDoesNotIssueRefreshTokenCookie() {
        AuthService authService = mock(AuthService.class);
        RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
        AuthController controller = new AuthController(authService, refreshTokenService);
        GuestLoginResponse response =
                new GuestLoginResponse("Bearer", "guest-access-token", 3_600L, AuthRole.GUEST);
        when(authService.guestLogin()).thenReturn(response);

        ResponseEntity<GuestLoginResponse> result = controller.guest();

        assertThat(result.getBody()).isEqualTo(response);
        assertThat(result.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
        verifyNoInteractions(refreshTokenService);
    }
}
