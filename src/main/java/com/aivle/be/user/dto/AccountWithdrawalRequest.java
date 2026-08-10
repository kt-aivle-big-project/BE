package com.aivle.be.user.dto;

import jakarta.validation.constraints.NotBlank;

public record AccountWithdrawalRequest(
        @NotBlank(message = "현재 비밀번호를 입력해주세요.") String password
) {
}
