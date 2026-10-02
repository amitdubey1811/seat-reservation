package com.amitdubey.seats.common;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;

/**
 * SQLSTATE inspection, so the service can distinguish "someone beat us to this row"
 * from "the database is genuinely unhappy".
 */
public final class PgErrors {

    /** unique_violation — a constraint we rely on as a second line of defense. */
    public static final String UNIQUE_VIOLATION = "23505";
    /** serialization_failure. */
    public static final String SERIALIZATION_FAILURE = "40001";
    /** deadlock_detected — should be unreachable given our lock ordering. */
    public static final String DEADLOCK_DETECTED = "40P01";
    /** lock_not_available / lock timeout. */
    public static final String LOCK_NOT_AVAILABLE = "55P03";
    /** query_canceled — statement_timeout fired. */
    public static final String QUERY_CANCELED = "57014";

    private PgErrors() {
    }

    /** Walks the cause chain for a SQLSTATE, since Spring wraps the original. */
    public static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    public static boolean isUniqueViolation(DataAccessException e) {
        return UNIQUE_VIOLATION.equals(sqlState(e));
    }

    /**
     * True when the failure is contention rather than a defect, so the caller may retry
     * or we may shed with a 429.
     */
    public static boolean isContention(Throwable t) {
        String state = sqlState(t);
        return SERIALIZATION_FAILURE.equals(state)
                || DEADLOCK_DETECTED.equals(state)
                || LOCK_NOT_AVAILABLE.equals(state)
                || QUERY_CANCELED.equals(state);
    }
}
