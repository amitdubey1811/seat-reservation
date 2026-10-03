package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.Burst;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * The central claim: hundreds of buyers fight over one seat and exactly one of them gets it.
 *
 * <p>These tests are the reason the service is built the way it is. Everything else — the
 * schema, the lock order, the error taxonomy — exists to make the assertions below hold.
 */
class HotSeatStormTest extends IntegrationTest {

    private static final int STORM = 300;

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    /** One token per buyer, minted up front so the burst measures reserving, not logging in. */
    private List<String> tokensFor(int count) {
        List<String> tokens = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tokens.add(api.userToken("buyer-" + i));
        }
        return tokens;
    }

    @Test
    @DisplayName("300 buyers storm one seat: exactly one wins, nobody sees a 5xx")
    void exactlyOneBuyerWinsAContestedSeat() {
        ShowResponse show = api.createShow("on-sale", labels(50), 25_000L);
        String showId = show.id().toString();
        List<String> tokens = tokensFor(STORM);
        double backstopBefore = counter("seat_backstop_fired_total");

        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        Burst.Result result = Burst.fire(STORM, () -> {
            int i = attempt.getAndIncrement();
            ResponseEntity<String> response = api.reserve(
                    tokens.get(i), showId, List.of("A12"), "key-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.count(201))
                .as("exactly one buyer may be told they got A12, out of %d: %s", STORM, result)
                .isEqualTo(1);
        assertThat(result.serverErrors())
                .as("a lost race is a domain outcome, never a server error: %s", result)
                .isZero();
        assertThat(result.count(409))
                .as("every loser gets a clean conflict: %s", result)
                .isEqualTo(STORM - 1);

        // The database is the real witness: one confirmed seat, owned by one user.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE label = 'A12' AND status = 'CONFIRMED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT owner_id) FROM seats WHERE label = 'A12' "
                        + "AND owner_id IS NOT NULL", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE label = 'A12' "
                        + "AND released_at IS NULL", Long.class))
                .as("one live claim on the seat, enforced by the partial unique index")
                .isEqualTo(1L);

        // This is what distinguishes "the conditional update is doing the work" from "the
        // database is quietly covering for it". Both produce correct outcomes, so without
        // this assertion the suite would pass even if the acquisition guard were removed —
        // and we would never learn that the primary mechanism had stopped working.
        assertThat(counter("seat_backstop_fired_total") - backstopBefore)
                .as("the unique index must never have to refuse anything: the conditional "
                        + "update should already have excluded every loser")
                .isZero();
    }

    @Test
    @DisplayName("a decline leaves nothing behind")
    void losersCreateNoReservations() {
        ShowResponse show = api.createShow("clean-rollback", labels(10), 500L);
        String showId = show.id().toString();
        List<String> tokens = tokensFor(STORM);

        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        Burst.fire(STORM, () -> {
            int i = attempt.getAndIncrement();
            ResponseEntity<String> response = api.reserve(
                    tokens.get(i), showId, List.of("A1"), "key-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(countRows("reservations"))
                .as("299 rolled-back attempts must not leave 299 orphan bookings")
                .isEqualTo(1L);
        assertThat(countRows("reservation_seats")).isEqualTo(1L);
        assertThat(countRows("idempotency_keys"))
                .as("a declined request's key rolls back with it, so the caller may retry")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the reconciliation invariant survives the burst")
    void availablePlusConfirmedAlwaysEqualsTotal() {
        ShowResponse show = api.createShow("invariant-under-load", labels(100), 500L);
        String showId = show.id().toString();
        List<String> tokens = tokensFor(STORM);
        List<String> hotSeats = List.of("A1", "A2", "A3", "A4", "A5");

        var attempt = new java.util.concurrent.atomic.AtomicInteger();
        Burst.Result result = Burst.fire(STORM, () -> {
            int i = attempt.getAndIncrement();
            // Many buyers, a handful of good seats — the shape of a real on-sale.
            ResponseEntity<String> response = api.reserve(
                    tokens.get(i), showId, List.of(hotSeats.get(i % hotSeats.size())),
                    "key-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.serverErrors()).as("%s", result).isZero();
        assertThat(result.count(201))
                .as("five contested seats means five winners, no more: %s", result)
                .isEqualTo(hotSeats.size());

        ShowResponse after = api.getShow(showId).getBody();
        assertThat(after).isNotNull();
        assertThat(after.counts().reconciled())
                .as("available + held + confirmed must still equal total_seats")
                .isTrue();
        assertThat(after.counts().confirmed()).isEqualTo(hotSeats.size());
        assertThat(after.counts().available()).isEqualTo(100 - hotSeats.size());
    }

    @Test
    @DisplayName("a seat that does not exist is a 404, not a conflict")
    void anUnknownSeatIsNotFound() {
        ShowResponse show = api.createShow("unknown-seat", labels(3), 500L);

        ResponseEntity<String> response = api.reserve(api.userToken("alice"),
                show.id().toString(), List.of("Z99"), UUID.randomUUID().toString(),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("seat_unknown");
        assertThat(countRows("reservations")).isZero();
    }
}
