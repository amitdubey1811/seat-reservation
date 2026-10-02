package com.amitdubey.seats.filter;

import com.amitdubey.seats.auth.AuthenticatedUser;
import com.amitdubey.seats.exception.ApiError;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reads the caller that {@link AuthFilter} resolved for this request.
 *
 * <p>Endpoints state their own requirements: {@link #require()} for anything needing
 * identity, {@link #requireAdmin()} for admin-only routes. The filter deliberately does
 * not enforce either, so public endpoints stay public without anyone maintaining a list
 * of exempt paths — a list that silently fails open the moment someone forgets to add a
 * new route to it.
 */
public final class CurrentUser {

    static final String ATTRIBUTE = CurrentUser.class.getName();

    private CurrentUser() {
    }

    /** The caller, or {@code null} when the request carried no token. */
    public static AuthenticatedUser orNull() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof AuthenticatedUser user ? user : null;
    }

    /** The caller, or 401. */
    public static AuthenticatedUser require() {
        AuthenticatedUser user = orNull();
        if (user == null) {
            throw ApiError.UNAUTHENTICATED.asException(
                    "This endpoint requires a bearer token.");
        }
        return user;
    }

    /** The caller, or 401 if absent and 403 if not an admin. */
    public static AuthenticatedUser requireAdmin() {
        AuthenticatedUser user = require();
        if (!user.isAdmin()) {
            throw ApiError.FORBIDDEN.asException("This endpoint requires an admin token.");
        }
        return user;
    }
}
