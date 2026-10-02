package com.amitdubey.seats.auth;

import com.amitdubey.seats.user.UserRole;
import java.util.UUID;

/**
 * Who the caller is, derived entirely from the signed token.
 *
 * <p>Nothing in a request body ever contributes to this. A body field named
 * {@code user_id} is not validated against the token — it is never read at all.
 */
public record AuthenticatedUser(UUID id, String handle, UserRole role) {

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
