package com.aivle.be.auth.service;

import com.aivle.be.auth.dto.response.EmailVerificationResponse;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

@Service
public class EmailVerificationService {

    private static final String CODE_PREFIX = "auth:email-verification:code:";
    private static final String COOLDOWN_PREFIX = "auth:email-verification:cooldown:";
    private static final String ATTEMPT_PREFIX = "auth:email-verification:attempt:";
    private static final String TOKEN_PREFIX = "auth:email-verification:token:";
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final VerificationEmailSender emailSender;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final Duration verificationTokenTtl;

    public EmailVerificationService(
            StringRedisTemplate redisTemplate,
            UserRepository userRepository,
            VerificationEmailSender emailSender,
            @Value("${auth.email-verification.code-ttl:5m}") Duration codeTtl,
            @Value("${auth.email-verification.resend-cooldown:60s}") Duration resendCooldown,
            @Value("${auth.email-verification.token-ttl:10m}") Duration verificationTokenTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.codeTtl = codeTtl;
        this.resendCooldown = resendCooldown;
        this.verificationTokenTtl = verificationTokenTtl;
    }

    public void sendCode(String rawEmail) {
        String email = normalize(rawEmail);
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        Boolean accepted = redisTemplate.opsForValue().setIfAbsent(
                cooldownKey(email), "1", resendCooldown
        );
        if (!Boolean.TRUE.equals(accepted)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_RATE_LIMITED);
        }

        String code = "%06d".formatted(secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(email), hash(email + ":" + code), codeTtl);
        redisTemplate.delete(attemptKey(email));

        try {
            emailSender.sendVerificationCode(email, code, codeTtl.toMinutes());
        } catch (RuntimeException exception) {
            redisTemplate.delete(codeKey(email));
            redisTemplate.delete(cooldownKey(email));
            throw exception;
        }
    }

    public EmailVerificationResponse verifyCode(String rawEmail, String code) {
        String email = normalize(rawEmail);
        String storedHash = redisTemplate.opsForValue().get(codeKey(email));
        if (storedHash == null) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND);
        }

        if (!constantTimeEquals(storedHash, hash(email + ":" + code))) {
            Long attempts = redisTemplate.opsForValue().increment(attemptKey(email));
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(attemptKey(email), codeTtl);
            }
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(codeKey(email));
                throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
            }
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_MISMATCH);
        }

        redisTemplate.delete(codeKey(email));
        redisTemplate.delete(attemptKey(email));
        String token = randomToken();
        redisTemplate.opsForValue().set(tokenKey(token), email, verificationTokenTtl);
        return new EmailVerificationResponse(token);
    }

    public void consumeVerification(String rawEmail, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }
        String email = normalize(rawEmail);
        String verifiedEmail = redisTemplate.opsForValue().getAndDelete(tokenKey(token));
        if (!email.equals(verifiedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String codeKey(String email) {
        return CODE_PREFIX + hash(email);
    }

    private String cooldownKey(String email) {
        return COOLDOWN_PREFIX + hash(email);
    }

    private String attemptKey(String email) {
        return ATTEMPT_PREFIX + hash(email);
    }

    private String tokenKey(String token) {
        return TOKEN_PREFIX + hash(token);
    }
}
