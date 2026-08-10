package com.aivle.be.user.service;

import com.aivle.be.auth.security.AuthenticatedRequester;
import com.aivle.be.global.exception.BusinessException;
import com.aivle.be.global.exception.ErrorCode;
import com.aivle.be.user.dto.AccountWithdrawalRequest;
import com.aivle.be.user.dto.PasswordChangeRequest;
import com.aivle.be.user.dto.UserProfileResponse;
import com.aivle.be.user.dto.UserProfileUpdateRequest;
import com.aivle.be.user.entity.User;
import com.aivle.be.user.repository.UserRepository;
import com.aivle.be.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserAccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WarehouseRepository warehouseRepository;

    public UserProfileResponse getProfile(AuthenticatedRequester requester) {
        return UserProfileResponse.from(requireUser(requester));
    }

    @Transactional
    public UserProfileResponse updateProfile(UserProfileUpdateRequest request, AuthenticatedRequester requester) {
        User user = requireUser(requester);
        user.updateName(request.name().trim());
        return UserProfileResponse.from(user);
    }

    @Transactional
    public void changePassword(PasswordChangeRequest request, AuthenticatedRequester requester) {
        User user = requireUser(requester);
        verifyPassword(request.currentPassword(), user);
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.SAME_PASSWORD);
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional
    public void withdraw(AccountWithdrawalRequest request, AuthenticatedRequester requester) {
        User user = requireUser(requester);
        verifyPassword(request.password(), user);
        if (warehouseRepository.existsByUser_IdAndSharedTrue(user.getId())) {
            throw new BusinessException(ErrorCode.SHARED_WAREHOUSE_OWNER_WITHDRAWAL_NOT_ALLOWED);
        }
        userRepository.delete(user);
        userRepository.flush();
    }

    private User requireUser(AuthenticatedRequester requester) {
        if (requester == null || !requester.isUser()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        User user = userRepository.findById(requester.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return user;
    }

    private void verifyPassword(String rawPassword, User user) {
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
        }
    }
}
