package com.amitdubey.seats.exception;

import java.time.Instant;

/**
 * Error body shape. Serialised snake_case (see spring.jackson.property-naming-strategy),
 * so this reaches clients as {"code":..., "message":..., "request_id":..., "at":...}.
 *
 * <p>{@code code} is the machine-readable reason a client (or our burst script) buckets
 * outcomes by; {@code requestId} is the correlation id to grep the logs with.
 */
public record ErrorResponse(String code, String message, String requestId, Instant at) {

    public static ErrorResponse of(ApiError error, String message, String requestId) {
        return new ErrorResponse(error.code(), message, requestId, Instant.now());
    }
}
