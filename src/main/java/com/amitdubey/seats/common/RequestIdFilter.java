package com.amitdubey.seats.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every request: honours an inbound {@code X-Request-Id} so a
 * caller's id survives into our logs, otherwise mints one. Echoed back as a response
 * header so the burst script can tie a specific 409 to a specific log line.
 *
 * <p>Runs before everything else, including auth, so even a rejected request is traceable.
 */
@Component
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final int ORDER = -200;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(RequestId.HEADER);
        String requestId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString()
                : incoming.substring(0, Math.min(incoming.length(), 128));

        MDC.put(RequestId.MDC_KEY, requestId);
        response.setHeader(RequestId.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }
}
