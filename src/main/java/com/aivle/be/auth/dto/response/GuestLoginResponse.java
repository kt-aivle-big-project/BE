package com.aivle.be.auth.dto.response;

import com.aivle.be.auth.jwt.AuthRole;
import io.swagger.v3.oas.annotations.media.Schema;

public record GuestLoginResponse(
        @Schema(example = "Bearer") String tokenType,
        String accessToken,
        @Schema(description = "Access Token expiration time in seconds", example = "3600") long expiresIn,
        @Schema(example = "GUEST") AuthRole role
) {
}
