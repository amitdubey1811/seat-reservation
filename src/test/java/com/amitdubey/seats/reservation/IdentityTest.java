package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.auth.dto.TokenRequest;
import com.amitdubey.seats.auth.dto.TokenResponse;
import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Identity comes from the signature or not at all.
 *
 * <p>The spec is specific about this: a request that tries to act "as" another user can only
 * ever act as the token's user. Note that the service does not <em>validate</em> a body
 * {@code user_id} against the token — it never reads it, which is a stronger property and one
 * that cannot drift as the DTO changes.
 */
class IdentityTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    private UUID userIdOf(String handle) {
        ResponseEntity<TokenResponse> response = http.postForEntity(
                "/auth/token", new TokenRequest(handle, null), TokenResponse.class);
        return response.getBody().userId();
    }

    @Test
    @DisplayName("a spoofed user_id in the body is ignored entirely")
    void theBookingBelongsToTheTokensUser() {
        ShowResponse show = api.createShow("spoof", labels(5), 500L);
        UUID victim = userIdOf("victim");
        UUID attacker = userIdOf("attacker");

        ResponseEntity<ReservationResponse> response = api.reserveRaw(
                api.userToken("attacker"), show.id().toString(),
                Map.of("seats", List.of("A1"),
                        "idempotency_key", "spoof-attempt",
                        "user_id", victim.toString()),
                ReservationResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().userId())
                .as("the booking belongs to the token holder, not to whoever the body named")
                .isEqualTo(attacker);

        assertThat(jdbc.queryForObject(
                "SELECT owner_id FROM seats WHERE label = 'A1'", UUID.class))
                .isEqualTo(attacker);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE user_id = ?", Long.class, victim))
                .as("the victim has no booking they did not make")
                .isZero();
    }

    @Test
    @DisplayName("a spoofed body cannot bypass another user's seat limit either")
    void spoofingDoesNotBorrowSomeoneElsesAllowance() {
        ShowResponse show = api.createShow("spoof-limit", labels(20), 500L);
        String showId = show.id().toString();
        UUID innocent = userIdOf("innocent");
        String attacker = api.userToken("attacker");

        for (int i = 1; i <= 4; i++) {
            api.reserve(attacker, showId, List.of("A" + i), "k" + i, String.class);
        }

        ResponseEntity<String> fifth = api.reserveRaw(attacker, showId,
                Map.of("seats", List.of("A5"), "idempotency_key", "k5",
                        "user_id", innocent.toString()),
                String.class);

        assertThat(fifth.getStatusCode().value())
                .as("the limit is counted against the token's user regardless of the body")
                .isEqualTo(409);
        assertThat(fifth.getBody()).contains("per_user_limit");
    }

    @Test
    @DisplayName("reserving without a token is refused")
    void anonymousReservationIsRefused() {
        ShowResponse show = api.createShow("anon", labels(5), 500L);

        ResponseEntity<String> response = api.reserve(
                null, show.id().toString(), List.of("A1"), "anon-key", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(countRows("reservations")).isZero();
    }

    @Test
    @DisplayName("a forged token is refused")
    void aTamperedTokenIsRefused() {
        ShowResponse show = api.createShow("forged", labels(5), 500L);
        String token = api.userToken("alice");
        String forged = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "BB" : "AA");

        ResponseEntity<String> response = api.reserve(
                forged, show.id().toString(), List.of("A1"), "forged-key", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(countRows("reservations")).isZero();
    }

    @Test
    @DisplayName("two users' identities never bleed into each other")
    void concurrentBuyersKeepTheirOwnSeats() {
        ShowResponse show = api.createShow("separate", labels(10), 500L);
        String showId = show.id().toString();
        UUID aliceId = userIdOf("alice");
        UUID bobId = userIdOf("bob");

        api.reserve(api.userToken("alice"), showId, List.of("A1"), "a1", String.class);
        api.reserve(api.userToken("bob"), showId, List.of("A2"), "b1", String.class);

        assertThat(jdbc.queryForObject(
                "SELECT owner_id FROM seats WHERE label = 'A1'", UUID.class)).isEqualTo(aliceId);
        assertThat(jdbc.queryForObject(
                "SELECT owner_id FROM seats WHERE label = 'A2'", UUID.class)).isEqualTo(bobId);
    }
}
