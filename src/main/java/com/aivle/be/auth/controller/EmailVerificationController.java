package com.aivle.be.auth.controller;

import com.aivle.be.auth.dto.request.EmailVerificationSendRequest;
import com.aivle.be.auth.dto.request.EmailVerificationVerifyRequest;
import com.aivle.be.auth.dto.response.EmailVerificationResponse;
import com.aivle.be.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping("/send")
    public ResponseEntity<Void> send(
            @Valid @RequestBody EmailVerificationSendRequest request
    ) {
        emailVerificationService.sendCode(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    public ResponseEntity<EmailVerificationResponse> verify(
            @Valid @RequestBody EmailVerificationVerifyRequest request
    ) {
        return ResponseEntity.ok(
                emailVerificationService.verifyCode(request.email(), request.code())
        );
    }
}
