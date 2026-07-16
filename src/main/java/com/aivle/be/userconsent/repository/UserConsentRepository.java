package com.aivle.be.userconsent.repository;

import com.aivle.be.userconsent.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {
}
