package com.aivle.be.auth.service;

import com.aivle.be.auth.dto.request.SignupRequest;
import com.aivle.be.auth.dto.request.LoginRequest;
import com.aivle.be.auth.dto.response.GuestLoginResponse;
import com.aivle.be.auth.dto.response.LoginResponse;
import com.aivle.be.auth.dto.response.SignupResponse;
import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.auth.jwt.JwtTokenProvider;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.userconsent.entity.ConsentType;
import com.aivle.be.userconsent.entity.UserConsent;
import com.aivle.be.userconsent.repository.UserConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String PRIVACY_TERMS_VERSION = "1.0";
    private static final String SERVICE_TERMS_VERSION = "1.0";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 10;

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        emailVerificationService.consumeVerification(
                email,
                request.emailVerificationToken()
        );

        User user = new User(
                email,
                request.name().trim(),
                passwordEncoder.encode(request.password())
        );

        User savedUser = userRepository.save(user);
        userConsentRepository.save(new UserConsent(
                savedUser,
                ConsentType.PRIVACY_COLLECTION_AND_USE,
                PRIVACY_TERMS_VERSION,
                LocalDateTime.now()
        ));
        userConsentRepository.save(new UserConsent(
                savedUser,
                ConsentType.SERVICE_TERMS,
                SERVICE_TERMS_VERSION,
                LocalDateTime.now()
        ));

        return SignupResponse.from(savedUser);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        LocalDateTime now = LocalDateTime.now();

        if (user.isLocked(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordLoginFailure(MAX_LOGIN_ATTEMPTS, now.plusMinutes(LOCK_MINUTES));
            if (user.isLocked(now)) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.resetLoginFailures();
        return createLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse refreshAccessToken(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        return createLoginResponse(user);
    }

    public GuestLoginResponse guestLogin() {
        return new GuestLoginResponse(
                "Bearer",
                jwtTokenProvider.createGuestAccessToken(),
                jwtTokenProvider.getGuestAccessTokenExpirationSeconds(),
                AuthRole.GUEST
        );
    }

    private LoginResponse createLoginResponse(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user);
        return new LoginResponse(
                "Bearer",
                accessToken,
                jwtTokenProvider.getAccessTokenExpirationSeconds(),
                user.getId(),
                user.getEmail(),
                user.getName()
        );
    }
}
