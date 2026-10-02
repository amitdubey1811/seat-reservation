package com.amitdubey.seats.usershowlock;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link UserShowLock}. */
public class UserShowLockId implements Serializable {

    private UUID userId;
    private UUID showId;

    protected UserShowLockId() {
    }

    public UserShowLockId(UUID userId, UUID showId) {
        this.userId = userId;
        this.showId = showId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getShowId() {
        return showId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof UserShowLockId other
                && Objects.equals(userId, other.userId)
                && Objects.equals(showId, other.showId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, showId);
    }
}
