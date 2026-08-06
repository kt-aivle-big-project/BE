package com.aivle.be.auth.service;

public interface VerificationEmailSender {
    void sendVerificationCode(String email, String code, long expirationMinutes);
}
