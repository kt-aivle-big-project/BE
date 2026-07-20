package com.aivle.be.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(example = "Bearer") String tokenType,
        String accessToken,
        @Schema(description = "Access Token 유효시간(초)", example = "3600") long expiresIn,
        @Schema(example = "1") Long userId,
        @Schema(example = "user@example.com") String email,
        @Schema(example = "홍길동") String name
) {
}
