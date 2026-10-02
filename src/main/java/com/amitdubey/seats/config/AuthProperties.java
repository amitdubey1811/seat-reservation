package com.amitdubey.seats.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings, sourced from the environment.
 *
 * <p>These are infrastructure, not policy, so they live here rather than in the
 * {@code service_config} table: rotating a signing secret invalidates every token in
 * flight, which is a restart-shaped event, not something to change live.
 */
@ConfigurationProperties(prefix = "seats.auth")
@Getter
@Setter
public class AuthProperties {

    /** HMAC-SHA256 signing secret. Must be at least 32 bytes. */
    private String jwtSecret;

    /** How long a minted token stays valid. */
    private Duration tokenTtl = Duration.ofHours(12);

    /** Presenting this at the token endpoint mints an admin token. */
    private String adminSecret;

    /**
     * Fails startup rather than booting with a weak or missing secret. A service that
     * starts happily with a 6-character signing key is worse than one that refuses to
     * start, because nothing will tell you about it later.
     */
    @PostConstruct
    void validate() {
        int bytes = jwtSecret == null ? 0 : jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 32) {
            throw new IllegalStateException(
                    "seats.auth.jwt-secret must be at least 32 bytes for HS256 (got " + bytes
                            + "). Set the JWT_SECRET environment variable.");
        }
        if (adminSecret == null || adminSecret.isBlank()) {
            throw new IllegalStateException(
                    "seats.auth.admin-secret must not be blank. Set the ADMIN_SECRET "
                            + "environment variable.");
        }
        if (tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero()) {
            throw new IllegalStateException("seats.auth.token-ttl must be positive.");
        }
    }
}
