package com.amitdubey.seats.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amitdubey.seats.config.AuthProperties;
import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.exception.ApiException;
import com.amitdubey.seats.user.AppUser;
import com.amitdubey.seats.user.UserRole;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * No Spring context and no database: signing and verification are pure computation, which
 * is the point — identity costs nothing on the hot path.
 */
class JwtServiceTest {

    private static final String SECRET = "test-signing-secret-at-least-32-bytes-long";

    private static JwtService serviceWith(String secret, Duration ttl) {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret(secret);
        properties.setAdminSecret("admin");
        properties.setTokenTtl(ttl);
        return new JwtService(properties);
    }

    private static AppUser user(UserRole role) {
        return new AppUser(UUID.randomUUID(), "alice", role);
    }

    @Test
    void mintedTokenVerifiesBackToTheSameCaller() {
        JwtService service = serviceWith(SECRET, Duration.ofHours(1));
        AppUser alice = user(UserRole.USER);

        AuthenticatedUser verified = service.verify(service.mint(alice));

        assertThat(verified.id()).isEqualTo(alice.getId());
        assertThat(verified.handle()).isEqualTo("alice");
        assertThat(verified.role()).isEqualTo(UserRole.USER);
        assertThat(verified.isAdmin()).isFalse();
    }

    @Test
    void adminRoleSurvivesTheRoundTrip() {
        JwtService service = serviceWith(SECRET, Duration.ofHours(1));

        assertThat(service.verify(service.mint(user(UserRole.ADMIN))).isAdmin()).isTrue();
    }

    @Test
    void aTamperedPayloadIsRejected() {
        JwtService service = serviceWith(SECRET, Duration.ofHours(1));
        String token = service.mint(user(UserRole.USER));

        // Flip a character in the payload segment; the signature no longer matches.
        String[] parts = token.split("\\.");
        parts[1] = parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A");
        String tampered = String.join(".", parts);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).error())
                .isEqualTo(ApiError.UNAUTHENTICATED);
    }

    @Test
    void aTokenSignedWithAnotherSecretIsRejected() {
        String token = serviceWith("a-completely-different-secret-32-bytes!!", Duration.ofHours(1))
                .mint(user(UserRole.USER));

        assertThatThrownBy(() -> serviceWith(SECRET, Duration.ofHours(1)).verify(token))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        // Negative TTL puts expiry in the past, so the token is born stale.
        JwtService service = serviceWith(SECRET, Duration.ofSeconds(-60));

        assertThatThrownBy(() -> service.verify(service.mint(user(UserRole.USER))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).error())
                .isEqualTo(ApiError.UNAUTHENTICATED);
    }

    @Test
    void garbageIsRejectedWithoutLeakingWhy() {
        JwtService service = serviceWith(SECRET, Duration.ofHours(1));

        for (String nonsense : new String[] {"", "not-a-token", "a.b.c", "Bearer x"}) {
            assertThatThrownBy(() -> service.verify(nonsense))
                    .isInstanceOf(ApiException.class)
                    .hasMessage("Token is missing, malformed, or expired.");
        }
    }
}
