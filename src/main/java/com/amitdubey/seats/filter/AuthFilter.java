package com.amitdubey.seats.filter;

import com.amitdubey.seats.auth.JwtService;
import com.amitdubey.seats.exception.ApiException;
import com.amitdubey.seats.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller from the {@code Authorization} header.
 *
 * <p><strong>Authenticates, does not authorise.</strong> A request with no token passes
 * straight through with no caller attached, and endpoints that need identity ask for it
 * via {@link CurrentUser#require()}. Only a token that is <em>present and invalid</em> is
 * rejected here. That keeps genuinely public endpoints public without anyone maintaining
 * a list of exempt paths — the kind of list that fails open when a new route is added and
 * nobody remembers to update it.
 *
 * <p>Filters run outside {@code @RestControllerAdvice}, so this one has to render its own
 * error body. Without that, a bad token would come back as Tomcat's HTML error page
 * instead of our JSON taxonomy, and a client parsing our errors would choke on it. It
 * uses the Spring-configured {@link ObjectMapper}, so the shape and snake_case naming
 * match every other error the service emits.
 */
@Component
@Order(AuthFilter.ORDER)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    /** After {@link RequestIdFilter}, so even a rejected token is traceable in the logs. */
    public static final int ORDER = RequestIdFilter.ORDER + 10;

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                request.setAttribute(CurrentUser.ATTRIBUTE, jwtService.verify(token));
            } catch (ApiException e) {
                writeError(response, e);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.error().status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(e.error(), e.getMessage(), RequestId.current()));
    }
}
