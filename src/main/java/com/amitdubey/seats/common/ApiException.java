package com.amitdubey.seats.common;

/**
 * Carries an {@link ApiError} to the exception handler.
 *
 * <p>Stack traces are suppressed: these are expected domain outcomes thrown thousands of
 * times a second during a burst, and filling in a stack trace for each one is pure cost.
 */
public class ApiException extends RuntimeException {

    private final ApiError error;

    public ApiException(ApiError error, String message) {
        super(message, null, false, false);
        this.error = error;
    }

    public ApiError error() {
        return error;
    }
}
