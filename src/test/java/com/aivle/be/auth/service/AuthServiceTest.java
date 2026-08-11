package com.aivle.be.auth.service;

import com.aivle.be.auth.dto.request.SignupRequest;
import com.aivle.be.auth.dto.response.GuestLoginResponse;
import com.aivle.be.auth.dto.response.SignupResponse;
import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.userconsent.entity.UserConsent;
import com.aivle.be.userconsent.repository.UserConsentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private EmailVerificationService emailVerificationService;

    @InjectMocks
    private AuthService authService;

    @Test
    void signupCreatesUserWithNormalizedEmailAndEncodedPassword() {
        SignupRequest request = new SignupRequest(
                " User@Example.com ", "홍길동", "Password123!", "verified-token", true, true);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SignupResponse response = authService.signup(request);

        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.name()).isEqualTo("홍길동");
        verify(passwordEncoder).encode("Password123!");
        verify(emailVerificationService).consumeVerification(
                "user@example.com",
                "verified-token"
        );
        verify(userRepository).save(any(User.class));
        verify(userConsentRepository, times(2)).save(any(UserConsent.class));
    }

    @Test
    void signupRejectsDuplicateEmail() {
        SignupRequest request = new SignupRequest(
                "user@example.com", "홍길동", "Password123!", "verified-token", true, true);
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    void guestLoginIssuesOnlyAccessTokenWithoutPersistingUser() {
        when(jwtTokenProvider.createGuestAccessToken()).thenReturn("guest-access-token");
        when(jwtTokenProvider.getGuestAccessTokenExpirationSeconds()).thenReturn(3_600L);

        GuestLoginResponse response = authService.guestLogin();

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isEqualTo("guest-access-token");
        assertThat(response.expiresIn()).isEqualTo(3_600L);
        assertThat(response.role()).isEqualTo(AuthRole.GUEST);
        verifyNoInteractions(userRepository, userConsentRepository, passwordEncoder);
    }
}
