package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class CancellationTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    private String reserve(String token, String showId, String seat, String key) {
        ResponseEntity<ReservationResponse> response =
                api.reserve(token, showId, List.of(seat), key, ReservationResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody().reservationId().toString();
    }

    @Test
    @DisplayName("a cancelled seat is cleanly re-bookable, and the history survives")
    void aReleasedSeatCanBeSoldAgain() {
        ShowResponse show = api.createShow("rebook", labels(5), 500L);
        String showId = show.id().toString();
        String alice = api.userToken("alice");

        String reservation = reserve(alice, showId, "A1", "a1");
        api.cancel(alice, reservation);

        assertThat(api.reserve(api.userToken("bob"), showId, List.of("A1"), "b1", String.class)
                .getStatusCode().value())
                .as("the partial unique index allows a new live claim once the old one is released")
                .isEqualTo(201);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE label = 'A1'", Long.class))
                .as("both claims are kept as history")
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats WHERE label = 'A1' "
                        + "AND released_at IS NULL", Long.class))
                .as("but only one is live")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a stranger cannot cancel someone else's booking")
    void onlyTheOwnerMayCancel() {
        ShowResponse show = api.createShow("ownership", labels(5), 500L);
        String showId = show.id().toString();

        String reservation = reserve(api.userToken("alice"), showId, "A1", "a1");

        ResponseEntity<String> response =
                api.cancelRaw(api.userToken("intruder"), reservation, String.class);

        assertThat(response.getStatusCode().value())
                .as("404 rather than 403: a stranger has no business learning the id exists")
                .isEqualTo(404);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM seats WHERE label = 'A1'", String.class))
                .as("alice still has her seat")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("cancelling twice is not an error")
    void cancellationIsIdempotent() {
        ShowResponse show = api.createShow("double-cancel", labels(5), 500L);
        String showId = show.id().toString();
        String alice = api.userToken("alice");
        String reservation = reserve(alice, showId, "A1", "a1");

        ResponseEntity<ReservationResponse> first = api.cancel(alice, reservation);
        ResponseEntity<ReservationResponse> second = api.cancel(alice, reservation);

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getBody().status()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("cancelling returns every seat of a multi-seat booking")
    void allSeatsComeBack() {
        ShowResponse show = api.createShow("multi-cancel", labels(5), 500L);
        String showId = show.id().toString();
        String alice = api.userToken("alice");

        ResponseEntity<ReservationResponse> booked = api.reserve(alice, showId,
                List.of("A1", "A2", "A3"), "m1", ReservationResponse.class);
        api.cancel(alice, booked.getBody().reservationId().toString());

        ShowResponse after = api.getShow(showId).getBody();
        assertThat(after.counts().available()).isEqualTo(5);
        assertThat(after.counts().confirmed()).isZero();
        assertThat(after.counts().reconciled()).isTrue();
    }

    @Test
    @DisplayName("a release cannot resurrect a seat now confirmed to someone else")
    void releasingNeverStealsBack() {
        ShowResponse show = api.createShow("no-resurrect", labels(5), 500L);
        String showId = show.id().toString();
        String alice = api.userToken("alice");
        String bob = api.userToken("bob");

        String aliceBooking = reserve(alice, showId, "A1", "a1");
        api.cancel(alice, aliceBooking);
        api.reserve(bob, showId, List.of("A1"), "b1", String.class);

        // Alice cancels again. Her reservation no longer owns A1, and the release predicate
        // is scoped to her reservation id, so it must not touch bob's seat.
        api.cancel(alice, aliceBooking);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM seats WHERE label = 'A1'", String.class))
                .as("bob keeps his seat")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an unknown reservation is a 404")
    void cancellingSomethingThatDoesNotExist() {
        ResponseEntity<String> response =
                api.cancelRaw(api.userToken("alice"), UUID.randomUUID().toString(), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("cancelling needs a token")
    void anonymousCancellationIsRefused() {
        ShowResponse show = api.createShow("anon-cancel", labels(5), 500L);
        String reservation = reserve(api.userToken("alice"), show.id().toString(), "A1", "a1");

        assertThat(api.cancelRaw(null, reservation, String.class).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a booking is visible to its owner and to nobody else")
    void readingABookingRequiresOwnership() {
        ShowResponse show = api.createShow("read-booking", labels(5), 500L);
        String showId = show.id().toString();
        String alice = api.userToken("alice");
        String reservation = reserve(alice, showId, "A1", "a1");

        assertThat(api.getReservation(alice, reservation, String.class).getStatusCode().value())
                .isEqualTo(200);
        assertThat(api.getReservation(api.userToken("nosy"), reservation, String.class)
                .getStatusCode().value()).isEqualTo(404);
    }
}
