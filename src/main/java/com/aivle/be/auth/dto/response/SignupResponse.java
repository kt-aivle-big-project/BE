package com.aivle.be.auth.dto.response;

import com.aivle.be.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

public record SignupResponse(
        @Schema(example = "1") Long userId,
        @Schema(example = "user@example.com") String email,
        @Schema(example = "홍길동") String name
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getEmail(), user.getName());
    }
}
