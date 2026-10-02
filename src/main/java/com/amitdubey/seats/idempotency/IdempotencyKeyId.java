package com.amitdubey.seats.idempotency;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite key for {@link IdempotencyKey}.
 *
 * <p>Keys are scoped to the authenticated user, so two users may pick the same key
 * string without colliding. That scoping is part of the API contract.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IdempotencyKeyId implements Serializable {

    private UUID userId;
    private String idemKey;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof IdempotencyKeyId other
                && Objects.equals(userId, other.userId)
                && Objects.equals(idemKey, other.idemKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, idemKey);
    }
}
