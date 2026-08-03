package com.aivle.be.auth.security;

import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedRequesterResolverTest {

    private final AuthenticatedRequesterResolver resolver =
            new AuthenticatedRequesterResolver();

    @Test
    void resolvesUserSubjectAsLong() {
        AuthenticatedRequester requester = resolver.resolve(authentication(
                "42",
                AuthRole.USER
        ));

        assertThat(requester).isEqualTo(AuthenticatedRequester.user(42L));
    }

    @Test
    void resolvesGuestSubjectAsUuid() {
        String guestSessionId = UUID.randomUUID().toString();

        AuthenticatedRequester requester = resolver.resolve(authentication(
                guestSessionId,
                AuthRole.GUEST
        ));

        assertThat(requester).isEqualTo(
                AuthenticatedRequester.guest(guestSessionId)
        );
    }

    @Test
    void rejectsInvalidRoleOrPrincipal() {
        assertInvalid(authentication("not-a-number", AuthRole.USER));
        assertInvalid(authentication("not-a-uuid", AuthRole.GUEST));
        assertInvalid(new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_UNKNOWN"))
        ));
    }

    @Test
    void rejectsAuthenticationWithUserAndGuestRolesTogether() {
        assertInvalid(new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(
                        new SimpleGrantedAuthority(AuthRole.USER.authority()),
                        new SimpleGrantedAuthority(AuthRole.GUEST.authority())
                )
        ));
    }

    @Test
    void rejectsUnauthenticatedAuthentication() {
        assertInvalid(new UsernamePasswordAuthenticationToken("1", null));
    }

    private UsernamePasswordAuthenticationToken authentication(
            String principal,
            AuthRole role
    ) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(role.authority()))
        );
    }

    private void assertInvalid(UsernamePasswordAuthenticationToken authentication) {
        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessException) exception).getErrorCode()
                ).isEqualTo(ErrorCode.INVALID_TOKEN));
    }
}
