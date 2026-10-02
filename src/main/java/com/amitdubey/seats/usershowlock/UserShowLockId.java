package com.amitdubey.seats.usershowlock;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/** Composite key for {@link UserShowLock}. */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserShowLockId implements Serializable {

    private UUID userId;
    private UUID showId;

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
