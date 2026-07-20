package com.aivle.be.auth.jwt;

import com.aivle.be.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtTokenProviderTest {

    @Test
    void createsAndParsesSignedAccessToken() {
        String testSecret = Encoders.BASE64.encode(Jwts.SIG.HS256.key().build().getEncoded());
        JwtTokenProvider provider = new JwtTokenProvider(testSecret, 3_600_000L);
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getEmail()).thenReturn("user@example.com");

        String token = provider.createAccessToken(user);
        Claims claims = provider.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("email", String.class)).isEqualTo("user@example.com");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }
}
