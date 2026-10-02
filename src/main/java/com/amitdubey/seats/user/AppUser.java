package com.amitdubey.seats.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user. Created on demand by the token endpoint.
 *
 * <p>Not read on the reservation path: identity arrives in the signed token, so reserve
 * never needs to look a user up.
 */
@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AppUser {

    @Setter(AccessLevel.NONE)
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String handle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public AppUser(UUID id, String handle, UserRole role) {
        this.id = id;
        this.handle = handle;
        this.role = role;
        this.createdAt = Instant.now();
    }
}
