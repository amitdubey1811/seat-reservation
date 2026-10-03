package com.amitdubey.seats.usershowlock;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserShowLockRepository extends JpaRepository<UserShowLock, UserShowLockId> {

    /**
     * Creates the mutex row if this is the user's first request for the show.
     *
     * <p>You cannot {@code SELECT ... FOR UPDATE} a row that does not exist, so it has to be
     * created first. {@code ON CONFLICT DO NOTHING} rather than catching a constraint
     * violation: with JPA, a {@code DataIntegrityViolationException} marks the transaction
     * rollback-only, which would poison everything that follows it.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_show_locks (user_id, show_id)
            VALUES (:userId, :showId)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("showId") UUID showId);

    /**
     * Takes this user's lock for this show, blocking until any sibling request lets go.
     *
     * <p>This is what makes the per-user limit hold under concurrency. The row carries no
     * data; being locked is its entire purpose. Because it is scoped to one user and one
     * show, different users never wait on each other — serialising one buyer costs nothing
     * globally.
     *
     * <p>First in the lock order after the idempotency key, and both of those are per-user,
     * so two different users can only ever meet on seat rows — which they climb in sorted
     * order. That is why a lock cycle cannot be constructed.
     */
    @Query(value = """
            SELECT 1
              FROM user_show_locks
             WHERE user_id = :userId
               AND show_id = :showId
               FOR UPDATE
            """, nativeQuery = true)
    Integer lockForUpdate(@Param("userId") UUID userId, @Param("showId") UUID showId);
}
