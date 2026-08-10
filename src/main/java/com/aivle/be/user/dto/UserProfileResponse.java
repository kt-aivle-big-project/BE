package com.aivle.be.user.dto;

import com.aivle.be.user.entity.User;

public record UserProfileResponse(Long userId, String email, String name) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(user.getId(), user.getEmail(), user.getName());
    }
}
