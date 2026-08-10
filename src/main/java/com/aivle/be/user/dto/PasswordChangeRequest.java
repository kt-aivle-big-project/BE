package com.aivle.be.user.dto;

import com.aivle.be.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record PasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호를 입력해주세요.") String currentPassword,
        @ValidPassword String newPassword
) {
}
