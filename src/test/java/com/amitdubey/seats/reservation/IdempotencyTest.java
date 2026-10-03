package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.Burst;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class IdempotencyTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    @Test
    @DisplayName("50 concurrent copies of one request create exactly one booking")
    void aKeyFiredConcurrentlyReservesOnce() {
        ShowResponse show = api.createShow("idem-race", labels(10), 25_000L);
        String showId = show.id().toString();
        String token = api.userToken("flaky-network");
        String key = "the-same-key";

        Burst.Result result = Burst.fire(50, () -> {
            ResponseEntity<String> response =
                    api.reserve(token, showId, List.of("A1"), key, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.serverErrors()).as("%s", result).isZero();
        assertThat(result.count(201))
                .as("one caller created the booking: %s", result)
                .isEqualTo(1);
        assertThat(result.count(200))
                .as("the other 49 were handed the booking that already existed: %s", result)
                .isEqualTo(49);

        assertThat(countRows("reservations"))
                .as("retries must move nothing extra")
                .isEqualTo(1L);
        assertThat(countRows("idempotency_keys")).isEqualTo(1L);

        // Every caller must have been told about the same booking.
        long distinctIds = result.bodies().stream()
                .map(body -> body.replaceAll(".*\"reservation_id\"\\s*:\\s*\"([^\"]+)\".*", "$1"))
                .distinct()
                .count();
        assertThat(distinctIds)
                .as("all 50 responses must name the same reservation")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a sequential retry returns the original booking with 200")
    void aRetryReplaysRatherThanRebooking() {
        ShowResponse show = api.createShow("idem-seq", labels(5), 25_000L);
        String showId = show.id().toString();
        String token = api.userToken("retrier");

        ResponseEntity<ReservationResponse> first =
                api.reserve(token, showId, List.of("A1"), "k", ReservationResponse.class);
        ResponseEntity<ReservationResponse> second =
                api.reserve(token, showId, List.of("A1"), "k", ReservationResponse.class);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value())
                .as("nothing was created the second time, so it is not a 201")
                .isEqualTo(200);
        assertThat(second.getBody().reservationId()).isEqualTo(first.getBody().reservationId());
        assertThat(second.getBody().amountPaise()).isEqualTo(25_000L);
        assertThat(countRows("reservations")).isEqualTo(1L);
    }

    @Test
    @DisplayName("the same key with different seats is refused")
    void reusingAKeyForADifferentRequestIsRejected() {
        ShowResponse show = api.createShow("idem-reuse", labels(5), 500L);
        String showId = show.id().toString();
        String token = api.userToken("confused-client");

        api.reserve(token, showId, List.of("A1"), "shared-key", String.class);
        ResponseEntity<String> second =
                api.reserve(token, showId, List.of("A2"), "shared-key", String.class);

        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).contains("idempotency_key_reuse");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE label = 'A2' AND status = 'CONFIRMED'",
                Long.class))
                .as("A2 must not have been taken by a request we refused")
                .isZero();
    }

    @Test
    @DisplayName("seat order does not change a request's identity")
    void thesameSeatsInADifferentOrderIsTheSameRequest() {
        ShowResponse show = api.createShow("idem-order", labels(5), 500L);
        String showId = show.id().toString();
        String token = api.userToken("reorderer");

        ResponseEntity<ReservationResponse> first = api.reserve(
                token, showId, List.of("A1", "A2"), "order-key", ReservationResponse.class);
        ResponseEntity<ReservationResponse> second = api.reserve(
                token, showId, List.of("A2", "A1"), "order-key", ReservationResponse.class);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value())
                .as("the hash is over sorted labels, so [A1,A2] and [A2,A1] are one request")
                .isEqualTo(200);
        assertThat(second.getBody().reservationId()).isEqualTo(first.getBody().reservationId());
    }

    @Test
    @DisplayName("keys are scoped per user, so two buyers may pick the same string")
    void twoUsersMayUseTheSameKey() {
        ShowResponse show = api.createShow("idem-scope", labels(5), 500L);
        String showId = show.id().toString();

        ResponseEntity<String> alice = api.reserve(
                api.userToken("alice"), showId, List.of("A1"), "uuid-collision", String.class);
        ResponseEntity<String> bob = api.reserve(
                api.userToken("bob"), showId, List.of("A2"), "uuid-collision", String.class);

        assertThat(alice.getStatusCode().value()).isEqualTo(201);
        assertThat(bob.getStatusCode().value())
                .as("one buyer's key choice cannot block another's")
                .isEqualTo(201);
        assertThat(countRows("reservations")).isEqualTo(2L);
    }

    @Test
    @DisplayName("retrying after a decline is a fresh attempt")
    void aDeclinedKeyIsReusable() {
        ShowResponse show = api.createShow("idem-after-decline", labels(5), 500L);
        String showId = show.id().toString();

        api.reserve(api.userToken("first"), showId, List.of("A1"), "x", String.class);

        // Bob loses the race for A1 with key "retry"; that key must not be burnt.
        String bob = api.userToken("bob");
        assertThat(api.reserve(bob, showId, List.of("A1"), "retry", String.class)
                .getStatusCode().value()).isEqualTo(409);

        assertThat(api.reserve(bob, showId, List.of("A2"), "retry", String.class)
                .getStatusCode().value())
                .as("a declined request rolls its key back, so the client may try again")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("the idempotency key may travel as a header")
    void theHeaderIsAcceptedInsteadOfABodyField() {
        ShowResponse show = api.createShow("idem-header", labels(5), 500L);
        String showId = show.id().toString();
        String token = api.userToken("header-user");

        var headers = api.bearer(token);
        headers.set("Idempotency-Key", "from-the-header");
        var entity = new org.springframework.http.HttpEntity<>(
                java.util.Map.of("seats", List.of("A1")), headers);

        ResponseEntity<String> first = http.exchange("/shows/" + showId + "/reserve",
                org.springframework.http.HttpMethod.POST, entity, String.class);
        ResponseEntity<String> second = http.exchange("/shows/" + showId + "/reserve",
                org.springframework.http.HttpMethod.POST, entity, String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(countRows("reservations")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a request with no key at all is rejected")
    void aMissingKeyIsABadRequest() {
        ShowResponse show = api.createShow("idem-missing", labels(5), 500L);

        ResponseEntity<String> response = api.reserveRaw(api.userToken("careless"),
                show.id().toString(), java.util.Map.of("seats", List.of("A1")), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(countRows("reservations")).isZero();
    }

    @Test
    @DisplayName("a different key cannot take a seat someone already holds")
    void idempotencyDoesNotWeakenSeatExclusivity() {
        ShowResponse show = api.createShow("idem-vs-seat", labels(5), 500L);
        String showId = show.id().toString();
        String token = api.userToken("double-dipper");

        assertThat(api.reserve(token, showId, List.of("A1"), "key-one", String.class)
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> again =
                api.reserve(token, showId, List.of("A1"), "key-two", String.class);

        assertThat(again.getStatusCode().value())
                .as("a fresh key is a fresh request, and the seat is gone")
                .isEqualTo(409);
        assertThat(again.getBody()).contains("seat_taken");
    }
}
