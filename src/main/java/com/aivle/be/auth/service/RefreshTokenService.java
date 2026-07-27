package com.aivle.be.auth.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "auth:refresh:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final Duration refreshTokenTtl;

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs
    ) {
        this.redisTemplate = redisTemplate;
        this.refreshTokenTtl = Duration.ofMillis(refreshTokenExpirationMs);
    }

    public String issue(Long userId) {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        redisTemplate.opsForValue().set(key(token), userId.toString(), refreshTokenTtl);
        return token;
    }

    public RotatedRefreshToken rotate(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String userId = redisTemplate.opsForValue().getAndDelete(key(refreshToken));
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Long parsedUserId;
        try {
            parsedUserId = Long.valueOf(userId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        return new RotatedRefreshToken(parsedUserId, issue(parsedUserId));
    }

    public void revoke(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            redisTemplate.delete(key(refreshToken));
        }
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenTtl.toSeconds();
    }

    private String key(String token) {
        return KEY_PREFIX + sha256(token);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record RotatedRefreshToken(Long userId, String token) {
    }
}
