package com.amitdubey.seats.serviceconfig;

/**
 * The known policy keys and their compile-time fallbacks.
 *
 * <p>The fallbacks are the last link in the chain {@code database row -> last good
 * snapshot -> built-in default}. They mean a missing row cannot stop the service, which
 * matters because the alternative is an outage caused by a {@code DELETE}.
 */
public final class ServiceConfigKeys {

    public static final String PER_USER_LIMIT = "reservation.per_user_limit";
    public static final String MAX_SEATS_PER_REQUEST = "reservation.max_seats_per_request";
    public static final String IDEMPOTENCY_RETENTION_HOURS = "idempotency.retention_hours";

    public static final int DEFAULT_PER_USER_LIMIT = 4;
    public static final int DEFAULT_MAX_SEATS_PER_REQUEST = 10;
    public static final int DEFAULT_IDEMPOTENCY_RETENTION_HOURS = 24;

    private ServiceConfigKeys() {
    }

    public static boolean isKnown(String key) {
        return PER_USER_LIMIT.equals(key)
                || MAX_SEATS_PER_REQUEST.equals(key)
                || IDEMPOTENCY_RETENTION_HOURS.equals(key);
    }
}
