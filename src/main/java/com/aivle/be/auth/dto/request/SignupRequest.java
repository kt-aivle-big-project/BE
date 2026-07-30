package com.aivle.be.auth.dto.request;

import com.aivle.be.auth.validation.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @Schema(description = "로그인 이메일", example = "user@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @Schema(description = "사용자 이름", example = "홍길동")
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 100, message = "이름은 100자 이하여야 합니다.")
        String name,

        @Schema(description = "8~24자이며 영문, 숫자, 특수문자 중 2종류 이상인 비밀번호", example = "Password123!")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @ValidPassword
        String password,

        @Schema(description = "개인정보 수집 및 이용 필수 동의", example = "true")
        @NotNull(message = "개인정보 수집 및 이용 동의 여부는 필수입니다.")
        @AssertTrue(message = "개인정보 수집 및 이용에 동의해야 합니다.")
        Boolean privacyAgreed,

        @Schema(description = "서비스 이용약관 필수 동의", example = "true")
        @NotNull(message = "서비스 이용약관 동의 여부는 필수입니다.")
        @AssertTrue(message = "서비스 이용약관에 동의해야 합니다.")
        Boolean serviceAgreed
) {
}
