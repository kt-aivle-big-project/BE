package com.aivle.be.auth.security;

import com.aivle.be.auth.jwt.AuthRole;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AuthenticatedRequesterResolver {

    public AuthenticatedRequester resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == null) {
            throw invalidToken();
        }

        String subject = authentication.getPrincipal().toString();
        if (subject.isBlank()) {
            throw invalidToken();
        }

        Set<AuthRole> roles = authentication.getAuthorities().stream()
                .map(authority -> toAuthRole(authority.getAuthority()))
                .filter(role -> role != null)
                .collect(Collectors.toSet());
        if (roles.size() != 1) {
            throw invalidToken();
        }

        AuthRole role = roles.iterator().next();
        if (role == AuthRole.USER) {
            return AuthenticatedRequester.user(parseUserId(subject));
        }
        return AuthenticatedRequester.guest(parseGuestSessionId(subject));
    }

    private AuthRole toAuthRole(String authority) {
        if (AuthRole.USER.authority().equals(authority)) {
            return AuthRole.USER;
        }
        if (AuthRole.GUEST.authority().equals(authority)) {
            return AuthRole.GUEST;
        }
        return null;
    }

    private Long parseUserId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw invalidToken();
        }
    }

    private String parseGuestSessionId(String subject) {
        try {
            return UUID.fromString(subject).toString();
        } catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    private BusinessException invalidToken() {
        return new BusinessException(ErrorCode.INVALID_TOKEN);
    }
}
