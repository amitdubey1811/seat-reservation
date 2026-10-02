package com.amitdubey.seats.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A request we have already answered.
 *
 * <p>Two absences are deliberate:
 *
 * <p><strong>No status column.</strong> The row is written and completed inside one
 * transaction, so another session sees either nothing at all or the finished row.
 * "Half-written" is not observable, so we do not model it. Verified with two live
 * sessions: the second {@code INSERT ... ON CONFLICT DO NOTHING} waits for the first to
 * commit, then reports zero rows — no error to catch.
 *
 * <p><strong>No stored response body.</strong> On replay we follow {@code reservationId}
 * and rebuild the answer from the booking itself, so there is one source of truth and a
 * replay reflects the booking's current state rather than a stale snapshot.
 */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyId.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IdempotencyKey {

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Setter(AccessLevel.NONE)
    @Id
    @Column(name = "idem_key")
    private String idemKey;

    /**
     * Hash of the canonical request: show id plus the sorted seat labels. A matching key
     * carrying a different hash is a client bug, and is rejected with 409 rather than
     * guessed at. No setter: mutating it after creation would defeat that check.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    /** Filled in once the booking exists, at the end of the same transaction. */
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public IdempotencyKey(UUID userId, String idemKey, String requestHash) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    public boolean matches(String candidateHash) {
        return requestHash.equals(candidateHash);
    }
}
