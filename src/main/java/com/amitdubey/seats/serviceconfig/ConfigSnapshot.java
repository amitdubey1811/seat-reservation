package com.amitdubey.seats.serviceconfig;

import java.time.Instant;

/**
 * An immutable set of policy values, swapped in wholesale by
 * {@link ServiceConfigService}.
 *
 * <p>Read from memory on every request, never from the database. Being up to a refresh
 * interval stale is safe here, and it is worth being precise about why: these are
 * <em>policy</em> numbers. The value that must be exact is the per-user seat
 * <em>count</em>, and that is read inside a row lock at reservation time. A slightly old
 * limit can decline a booking it would now allow, or allow one it would now decline; it
 * can never cause a seat to be sold twice or break the reconciliation invariant.
 *
 * @param version   increments on every successful load, so a reload is observable
 * @param loadedAt  when these values were read
 */
public record ConfigSnapshot(
        int perUserLimit,
        int maxSeatsPerRequest,
        int idempotencyRetentionHours,
        long version,
        Instant loadedAt) {

    /** Used before the first successful load, and if the table is empty. */
    public static ConfigSnapshot defaults() {
        return new ConfigSnapshot(
                ServiceConfigKeys.DEFAULT_PER_USER_LIMIT,
                ServiceConfigKeys.DEFAULT_MAX_SEATS_PER_REQUEST,
                ServiceConfigKeys.DEFAULT_IDEMPOTENCY_RETENTION_HOURS,
                0L,
                Instant.EPOCH);
    }
}
