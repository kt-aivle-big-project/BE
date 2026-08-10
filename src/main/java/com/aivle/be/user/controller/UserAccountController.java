package com.aivle.be.user.controller;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.auth.security.AuthenticatedRequesterResolver;
import com.aivle.be.auth.service.RefreshTokenService;
import com.aivle.be.user.dto.AccountWithdrawalRequest;
import com.aivle.be.user.dto.PasswordChangeRequest;
import com.aivle.be.user.dto.UserProfileResponse;
import com.aivle.be.user.dto.UserProfileUpdateRequest;
import com.aivle.be.user.service.UserAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User Account", description = "회원 정보 관리 API")
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserAccountController {

    private final UserAccountService userAccountService;
    private final AuthenticatedRequesterResolver requesterResolver;
    private final RefreshTokenService refreshTokenService;

    @Value("${jwt.refresh-cookie-secure:true}")
    private boolean refreshCookieSecure;

    @Operation(summary = "내 정보 조회")
    @GetMapping
    public ResponseEntity<UserProfileResponse> getProfile(Authentication authentication) {
        return ResponseEntity.ok(userAccountService.getProfile(requester(authentication)));
    }

    @Operation(summary = "내 정보 수정")
    @PatchMapping
    public ResponseEntity<UserProfileResponse> updateProfile(
            @Valid @RequestBody UserProfileUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(userAccountService.updateProfile(request, requester(authentication)));
    }

    @Operation(summary = "비밀번호 변경")
    @PatchMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            Authentication authentication
    ) {
        userAccountService.changePassword(request, requester(authentication));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "회원 탈퇴")
    @DeleteMapping
    public ResponseEntity<Void> withdraw(
            @Valid @RequestBody AccountWithdrawalRequest request,
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            Authentication authentication
    ) {
        userAccountService.withdraw(request, requester(authentication));
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .build();
    }

    private AuthenticatedRequester requester(Authentication authentication) {
        return requesterResolver.resolve(authentication);
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(0)
                .build();
    }
}
