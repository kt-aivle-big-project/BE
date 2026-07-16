package com.aivle.be.userconsent.entity;

import com.aivle.be.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_consents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_consents_user_type_version",
                columnNames = {"user_id", "consent_type", "terms_version"}
        )
)
@Getter
@NoArgsConstructor
public class UserConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 50)
    private ConsentType consentType;

    @Column(name = "terms_version", nullable = false, length = 20)
    private String termsVersion;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    public UserConsent(User user, ConsentType consentType, String termsVersion, LocalDateTime agreedAt) {
        this.user = user;
        this.consentType = consentType;
        this.termsVersion = termsVersion;
        this.agreedAt = agreedAt;
    }
}
