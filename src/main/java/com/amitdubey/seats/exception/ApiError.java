package com.amitdubey.seats.exception;

import org.springframework.http.HttpStatus;

/**
 * The complete error taxonomy. Every outcome this service can produce is listed here,
 * and all but {@link #INTERNAL} are 4xx.
 *
 * <p>This is deliberate: a declined reservation is a domain outcome, not a server fault.
 * Resource exhaustion (pool timeout, lock timeout, deadlock) is also expressed as a 4xx
 * ({@link #BUSY}, 429) rather than Spring's default 503, because a 5xx during a burst
 * would mean we failed to decide — and the service is the system of record.
 */
public enum ApiError {

    // --- reservation declines -------------------------------------------------
    SEAT_TAKEN(HttpStatus.CONFLICT, "seat_taken",
            "One or more requested seats are no longer available."),
    PER_USER_LIMIT(HttpStatus.CONFLICT, "per_user_limit",
            "This request would exceed the per-user seat limit for this show."),

    // --- idempotency ----------------------------------------------------------
    IDEMPOTENCY_KEY_REUSE(HttpStatus.CONFLICT, "idempotency_key_reuse",
            "This idempotency key was already used with a different request body."),
    // There is no "request in progress" outcome. The key row is written and completed
    // inside one transaction, so a concurrent duplicate either sees nothing or sees the
    // finished row — it waits on the insert and then replays. Verified with two live
    // sessions; see IdempotencyKey.

    // --- lookup ---------------------------------------------------------------
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND, "show_not_found", "No such show."),
    SEAT_UNKNOWN(HttpStatus.NOT_FOUND, "seat_unknown", "No such seat in this show."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "reservation_not_found", "No such reservation."),
    CONFIG_KEY_UNKNOWN(HttpStatus.NOT_FOUND, "config_key_unknown",
            "No such configuration key."),

    // --- request shape --------------------------------------------------------
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation_failed", "Request failed validation."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "malformed_request", "Request body could not be parsed."),
    TOO_MANY_SEATS(HttpStatus.BAD_REQUEST, "too_many_seats",
            "More seats requested than this service allows in one call."),
    DUPLICATE_SEATS(HttpStatus.BAD_REQUEST, "duplicate_seats",
            "The same seat was listed more than once."),

    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "route_not_found", "No such endpoint."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
            "That HTTP method is not supported on this endpoint."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
            "This endpoint accepts application/json."),

    // --- auth -----------------------------------------------------------------
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated", "Missing or invalid bearer token."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "This token may not perform that action."),

    // --- load shedding --------------------------------------------------------
    // We could not decide right now, but the database is healthy: the pool was busy or a
    // row was contended. A 4xx is the honest answer — we declined to decide, we did not
    // decline the booking on its merits.
    BUSY(HttpStatus.TOO_MANY_REQUESTS, "busy",
            "The service is saturated or the row is contended; retry shortly."),

    // --- dependency down ------------------------------------------------------
    // The database is unreachable. This is NOT dressed up as a 429: calling a dead
    // dependency "too many requests" would be a lie, and readiness is failing too, so
    // the platform should already be routing traffic away from us.
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "dependency_unavailable",
            "A dependency is unavailable; the service cannot serve requests right now."),

    // --- genuine bug ----------------------------------------------------------
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ApiError(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public ApiException asException() {
        return new ApiException(this, defaultMessage);
    }

    public ApiException asException(String message) {
        return new ApiException(this, message);
    }
}
