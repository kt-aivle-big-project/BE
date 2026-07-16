package com.aivle.be.auth.service;

import com.aivle.be.auth.dto.request.SignupRequest;
import com.aivle.be.auth.dto.response.SignupResponse;
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

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

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

        return SignupResponse.from(savedUser);
    }
}
