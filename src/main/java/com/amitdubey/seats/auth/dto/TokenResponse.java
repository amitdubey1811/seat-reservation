package com.amitdubey.seats.auth.dto;

import com.amitdubey.seats.user.UserRole;
import java.util.UUID;

/** Serialised snake_case: {@code access_token}, {@code user_id}, {@code expires_in_seconds}. */
public record TokenResponse(
        String accessToken,
        String tokenType,
        UUID userId,
        String handle,
        UserRole role,
        long expiresInSeconds) {
}
