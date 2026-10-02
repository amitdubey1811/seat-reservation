package com.amitdubey.seats.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Startup validation. A service that boots happily with a six-character signing key is
 * worse than one that refuses to boot, because nothing will tell you about it afterwards.
 */
class AuthPropertiesTest {

    private static final String GOOD_SECRET = "test-signing-secret-at-least-32-bytes-long";

    private static AuthProperties properties(String jwtSecret, String adminSecret) {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret(jwtSecret);
        properties.setAdminSecret(adminSecret);
        return properties;
    }

    @Test
    void acceptsASufficientSecret() {
        assertThatCode(properties(GOOD_SECRET, "admin")::validate).doesNotThrowAnyException();
    }

    @Test
    void refusesASecretUnder32Bytes() {
        assertThatThrownBy(properties("too-short", "admin")::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void refusesAMissingSecret() {
        assertThatThrownBy(properties(null, "admin")::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void refusesABlankAdminSecret() {
        assertThatThrownBy(properties(GOOD_SECRET, "   ")::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-secret");
    }

    @Test
    void refusesANonPositiveTokenTtl() {
        AuthProperties zeroTtl = properties(GOOD_SECRET, "admin");
        zeroTtl.setTokenTtl(Duration.ZERO);

        assertThatThrownBy(zeroTtl::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-ttl");
    }
}
