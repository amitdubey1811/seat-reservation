package com.amitdubey.seats.user;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Find-or-create for the token endpoint.
 *
 * <p>Our stand-in for an identity provider: in a real system users would already exist
 * and this would be a lookup. The point being demonstrated is not registration, it is
 * that identity reaches the rest of the service <em>only</em> through a signed token.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;


    /**
     * Returns the user with this handle, creating it if absent.
     *
     * <p>{@code grantAdmin} promotes an existing user rather than creating a second
     * account, and never demotes: handing over the admin secret can only ever add
     * privilege, so a stray call with the secret omitted cannot lock an admin out.
     */
    @Transactional
    public AppUser findOrCreate(String handle, boolean grantAdmin) {
        UserRole role = grantAdmin ? UserRole.ADMIN : UserRole.USER;

        users.insertIfAbsent(UUID.randomUUID(), handle, role.name());
        if (grantAdmin) {
            users.promoteRole(handle, UserRole.ADMIN);
        }

        // Always read back rather than trusting what we just wrote: on a race another
        // request may have created this handle, and that row is the one that exists.
        return users.findByHandle(handle).orElseThrow(() ->
                new IllegalStateException("user vanished immediately after upsert: " + handle));
    }
}
