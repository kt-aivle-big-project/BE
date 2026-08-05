package com.aivle.be.auth.jwt;

import com.aivle.be.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long guestAccessTokenExpirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String encodedSecret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${jwt.guest-access-token-expiration-ms}") long guestAccessTokenExpirationMs
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodedSecret));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.guestAccessTokenExpirationMs = guestAccessTokenExpirationMs;
    }

    public String createAccessToken(User user) {
        return createAccessToken(
                user.getId().toString(),
                user.getEmail(),
                AuthRole.USER,
                accessTokenExpirationMs
        );
    }

    public String createGuestAccessToken() {
        return createAccessToken(
                UUID.randomUUID().toString(),
                null,
                AuthRole.GUEST,
                guestAccessTokenExpirationMs
        );
    }

    private String createAccessToken(
            String subject,
            String email,
            AuthRole role,
            long expirationMs
    ) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(subject)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)));

        if (email != null) {
            builder.claim("email", email);
        }

        return builder.signWith(signingKey).compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    public long getGuestAccessTokenExpirationSeconds() {
        return guestAccessTokenExpirationMs / 1000;
    }
}
