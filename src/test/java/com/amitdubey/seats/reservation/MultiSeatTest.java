package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.Burst;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class MultiSeatTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    @Test
    @DisplayName("all or nothing: one unavailable seat refuses the whole request")
    void aPartiallyAvailableRequestTakesNothing() {
        ShowResponse show = api.createShow("all-or-nothing", labels(5), 500L);
        String showId = show.id().toString();

        api.reserve(api.userToken("early-bird"), showId, List.of("A2"), "e1", String.class);

        ResponseEntity<String> response = api.reserve(api.userToken("latecomer"), showId,
                List.of("A1", "A2", "A3"), UUID.randomUUID().toString(), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("seat_taken");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE label IN ('A1','A3') AND status = 'AVAILABLE'",
                Long.class))
                .as("A1 and A3 must be left exactly as they were")
                .isEqualTo(2L);
        assertThat(countRows("reservations"))
                .as("only the early bird's booking exists")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a multi-seat booking charges price times seat count")
    void theAmountIsTheSumOfTheSeats() {
        ShowResponse show = api.createShow("pricing", labels(5), 25_000L);

        ResponseEntity<ReservationResponse> response = api.reserve(api.userToken("buyer"),
                show.id().toString(), List.of("A1", "A2", "A3"), "p1",
                ReservationResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().amountPaise())
                .as("three seats at 25000 paise each, as an integer")
                .isEqualTo(75_000L);
        assertThat(response.getBody().seats()).containsExactly("A1", "A2", "A3");
    }

    @Test
    @DisplayName("seats come back sorted whatever order they were asked for")
    void seatsAreReturnedInLabelOrder() {
        ShowResponse show = api.createShow("ordering", labels(9), 500L);

        ResponseEntity<ReservationResponse> response = api.reserve(api.userToken("buyer"),
                show.id().toString(), List.of("A9", "A1", "A5"), "o1",
                ReservationResponse.class);

        assertThat(response.getBody().seats()).containsExactly("A1", "A5", "A9");
    }

    @Test
    @DisplayName("overlapping multi-seat requests in opposing orders never deadlock")
    void opposingSeatOrdersCannotDeadlock() {
        // The classic deadlock setup: one transaction holds A1 and wants A2 while another
        // holds A2 and wants A1. Sorting labels before acquiring them is what makes this
        // impossible, so the assertion is that no request ever fails with a server error.
        ShowResponse show = api.createShow("deadlock", labels(20), 500L);
        String showId = show.id().toString();

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            tokens.add(api.userToken("pair-buyer-" + i));
        }

        AtomicInteger next = new AtomicInteger();
        Burst.Result result = Burst.fire(60, () -> {
            int i = next.getAndIncrement();
            int first = (i % 10) + 1;
            int second = first + 1;
            // Half the callers ask in ascending order, half descending, over overlapping
            // pairs — so the service, not the caller, has to impose a consistent order.
            List<String> seats = i % 2 == 0
                    ? List.of("A" + first, "A" + second)
                    : List.of("A" + second, "A" + first);
            ResponseEntity<String> response =
                    api.reserve(tokens.get(i), showId, seats, "dl-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.serverErrors())
                .as("a deadlock would surface as a 5xx; there must be none: %s", result)
                .isZero();
        assertThat(result.count(201) + result.count(409))
                .as("every request resolved one way or the other: %s", result)
                .isEqualTo(60);

        ShowResponse after = api.getShow(showId).getBody();
        assertThat(after.counts().reconciled()).isTrue();
        assertThat(after.counts().confirmed() % 2)
                .as("seats are only ever sold in the pairs that were requested")
                .isZero();
    }

    @Test
    @DisplayName("asking for the same seat twice in one request is rejected")
    void aRepeatedSeatInOneRequestIsABadRequest() {
        ShowResponse show = api.createShow("repeat", labels(5), 500L);

        ResponseEntity<String> response = api.reserve(api.userToken("buyer"),
                show.id().toString(), List.of("A1", "A1"), "r1", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("duplicate_seats");
        assertThat(countRows("reservations")).isZero();
    }

    @Test
    @DisplayName("more seats than the configured cap is refused")
    void exceedingTheRequestCapIsRejected() {
        setConfig("reservation.max_seats_per_request", "3");

        ShowResponse show = api.createShowRaw(api.adminToken("root"),
                new com.amitdubey.seats.show.dto.CreateShowRequest("cap", labels(20), 500L, 10),
                ShowResponse.class).getBody();

        ResponseEntity<String> response = api.reserve(api.userToken("bulk"),
                show.id().toString(), List.of("A1", "A2", "A3", "A4"), "c1", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("too_many_seats");
    }
}
