package com.aivle.be.auth.security;

import com.aivle.be.auth.jwt.AuthRole;

public record AuthenticatedRequester(
        AuthRole role,
        Long userId,
        String guestSessionId
) {
    public static AuthenticatedRequester user(Long userId) {
        return new AuthenticatedRequester(AuthRole.USER, userId, null);
    }

    public static AuthenticatedRequester guest(String guestSessionId) {
        return new AuthenticatedRequester(AuthRole.GUEST, null, guestSessionId);
    }

    public boolean isUser() {
        return role == AuthRole.USER;
    }

    public boolean isGuest() {
        return role == AuthRole.GUEST;
    }
}
