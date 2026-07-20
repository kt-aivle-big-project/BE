package com.aivle.be.auth.service;

import com.aivle.be.auth.dto.request.LoginRequest;
import com.aivle.be.auth.dto.response.LoginResponse;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.userconsent.repository.UserConsentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserConsentRepository userConsentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @InjectMocks
    private AuthService authService;

    @Test
    void loginReturnsAccessTokenForValidCredentials() {
        User user = new User("user@example.com", "User", "encoded-password");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123!", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.createAccessToken(user)).thenReturn("access-token");
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(3600L);

        LoginResponse response = authService.login(
                new LoginRequest(" User@Example.com ", "Password123!")
        );

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
    }

    @Test
    void loginRejectsUnknownEmailWithoutRevealingWhichCredentialFailed() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("unknown@example.com", "Password123!")
        )).isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void loginLocksAccountOnFifthPasswordFailure() {
        User user = new User("user@example.com", "User", "encoded-password");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);
        LoginRequest request = new LoginRequest("user@example.com", "wrong-password");

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_LOCKED));
        assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
    }
}
