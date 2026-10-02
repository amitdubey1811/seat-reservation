package com.amitdubey.seats.filter;

import org.slf4j.MDC;

/** Access to the current request's correlation id. */
public final class RequestId {

    public static final String MDC_KEY = "request_id";
    public static final String HEADER = "X-Request-Id";

    private RequestId() {
    }

    /** The current correlation id, or {@code "-"} outside a request (e.g. the sweeper). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "-" : id;
    }
}
