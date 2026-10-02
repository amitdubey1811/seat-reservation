package com.amitdubey.seats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A mutex, and nothing else. <strong>It deliberately has no data columns.</strong>
 *
 * <p>Its only purpose is to be locked with {@code SELECT ... FOR UPDATE} so that one
 * user's parallel reserve requests for one show take turns. Without it, ten concurrent
 * requests would each count the user's seats, each see the same number, and each decide
 * they were under the limit.
 *
 * <p>Two choices worth noting:
 *
 * <p><strong>The lock is per user, per show</strong>, so different users never wait for
 * each other. Serialising one user costs nothing globally.
 *
 * <p><strong>It holds no counter.</strong> The authoritative seat count is read from
 * {@code seats} while this row is locked. A counter column would be a second copy of the
 * truth, and if it ever drifted it would break the reconciliation invariant.
 */
@Entity
@Table(name = "user_show_locks")
@IdClass(UserShowLockId.class)
public class UserShowLock {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "show_id")
    private UUID showId;

    /** Required by JPA. */
    protected UserShowLock() {
    }

    public UserShowLock(UUID userId, UUID showId) {
        this.userId = userId;
        this.showId = showId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getShowId() {
        return showId;
    }
}
