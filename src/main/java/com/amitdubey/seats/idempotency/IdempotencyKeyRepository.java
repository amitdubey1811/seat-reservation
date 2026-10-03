package com.amitdubey.seats.idempotency;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    /**
     * Claims the key, or reports that someone already has.
     *
     * <p>This is the <strong>first</strong> statement of a reservation, before any lock or
     * count. If it came later, a retry from a user already at their seat limit would be
     * declined for the limit instead of replaying the booking they already own — the
     * idempotency requirement and the limit requirement would collide, and the limit would
     * win.
     *
     * <p>Behaviour under a concurrent duplicate, verified with two live sessions: the second
     * caller <em>waits</em> for the first transaction to resolve and then reports zero rows.
     * No {@code 23505}, nothing to catch. If the first committed, the row is now visible and
     * can be replayed; if it rolled back, this caller inserts and proceeds normally. That is
     * also why there is no "in progress" state to model — the key row and its reservation
     * are written in one transaction, so a half-written key is not observable.
     *
     * @return 1 if this caller claimed the key, 0 if it was already taken
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_keys (user_id, idem_key, request_hash, created_at)
            VALUES (:userId, :idemKey, :requestHash, now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("idemKey") String idemKey,
                       @Param("requestHash") String requestHash);

    /** Ties the key to the booking it produced, so a later retry can find it. */
    @Modifying
    @Query("""
            update IdempotencyKey k
               set k.reservationId = :reservationId
             where k.userId = :userId
               and k.idemKey = :idemKey
            """)
    int attachReservation(@Param("userId") UUID userId,
                          @Param("idemKey") String idemKey,
                          @Param("reservationId") UUID reservationId);
}
