package com.aivle.be.auth.service;

import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class GmailVerificationEmailSender implements VerificationEmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public GmailVerificationEmailSender(
            JavaMailSender mailSender,
            @Value("${mail.from:${spring.mail.username:}}") String from
    ) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendVerificationCode(String email, String code, long expirationMinutes) {
        if (from == null || from.isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("[LARO] 회원가입 이메일 인증번호");
        message.setText("LARO 회원가입 인증번호는 " + code + " 입니다.\n"
                + expirationMinutes + "분 안에 입력해주세요.\n"
                + "본인이 요청하지 않았다면 이 메일을 무시해주세요.");
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }
}
