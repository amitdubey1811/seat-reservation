package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The limit has to hold when one buyer fires everything at once, which is the case the
 * obvious implementation gets wrong: ten parallel requests each count the same number of
 * seats and each conclude they are under the cap.
 */
class PerUserLimitTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    @Test
    @DisplayName("one buyer firing 10 parallel reserves on a limit of 4 ends with 4")
    void theLimitHoldsUnderOneUsersOwnConcurrency() {
        ShowResponse show = api.createShow("limit", labels(20), 500L);
        String showId = show.id().toString();
        String token = api.userToken("greedy");
        List<String> wanted = labels(10);

        AtomicInteger next = new AtomicInteger();
        Burst.Result result = Burst.fire(10, () -> {
            int i = next.getAndIncrement();
            ResponseEntity<String> response = api.reserve(
                    token, showId, List.of(wanted.get(i)), "key-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.serverErrors()).as("%s", result).isZero();
        assertThat(result.count(201))
                .as("at most four may succeed, no matter how they are interleaved: %s", result)
                .isEqualTo(4);
        assertThat(result.count(409)).isEqualTo(6);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE status = 'CONFIRMED'", Long.class))
                .isEqualTo(4L);
    }

    @Test
    @DisplayName("going over the limit is a clean decline, not an error")
    void theFifthSeatIsDeclinedWithAReason() {
        ShowResponse show = api.createShow("limit-seq", labels(10), 500L);
        String showId = show.id().toString();
        String token = api.userToken("steady");

        for (int i = 1; i <= 4; i++) {
            assertThat(api.reserve(token, showId, List.of("A" + i), "k" + i, String.class)
                    .getStatusCode().value()).isEqualTo(201);
        }

        ResponseEntity<String> fifth =
                api.reserve(token, showId, List.of("A5"), "k5", String.class);

        assertThat(fifth.getStatusCode().value()).isEqualTo(409);
        assertThat(fifth.getBody()).contains("per_user_limit");
    }

    @Test
    @DisplayName("a multi-seat request that would breach the limit is refused whole")
    void aRequestThatWouldBreachTheLimitTakesNoSeats() {
        ShowResponse show = api.createShow("limit-multi", labels(10), 500L);
        String showId = show.id().toString();
        String token = api.userToken("bulk");

        ResponseEntity<String> response = api.reserve(token, showId,
                List.of("A1", "A2", "A3", "A4", "A5"), UUID.randomUUID().toString(),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).contains("per_user_limit");
        assertThat(countRows("reservations")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE status = 'CONFIRMED'", Long.class))
                .as("no partial allocation: five requested, five refused")
                .isZero();
    }

    @Test
    @DisplayName("the limit is per user, so other buyers are unaffected")
    void oneBuyersLimitDoesNotConstrainAnother() {
        ShowResponse show = api.createShow("limit-per-user", labels(20), 500L);
        String showId = show.id().toString();

        String alice = api.userToken("alice");
        for (int i = 1; i <= 4; i++) {
            api.reserve(alice, showId, List.of("A" + i), "a" + i, String.class);
        }
        assertThat(api.reserve(alice, showId, List.of("A5"), "a5", String.class)
                .getStatusCode().value()).isEqualTo(409);

        assertThat(api.reserve(api.userToken("bob"), showId, List.of("A5"), "b1", String.class)
                .getStatusCode().value())
                .as("bob has his own allowance")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("cancelling frees allowance again")
    void cancellingReleasesTheLimit() {
        ShowResponse show = api.createShow("limit-cancel", labels(10), 500L);
        String showId = show.id().toString();
        String token = api.userToken("churner");

        String firstReservation = null;
        for (int i = 1; i <= 4; i++) {
            var response = api.reserve(token, showId, List.of("A" + i), "c" + i,
                    com.amitdubey.seats.reservation.dto.ReservationResponse.class);
            if (i == 1) {
                firstReservation = response.getBody().reservationId().toString();
            }
        }

        assertThat(api.reserve(token, showId, List.of("A9"), "c9", String.class)
                .getStatusCode().value()).isEqualTo(409);

        api.cancel(token, firstReservation);

        assertThat(api.reserve(token, showId, List.of("A9"), "c9-again", String.class)
                .getStatusCode().value())
                .as("a cancelled seat gives the allowance back")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("a per-show override replaces the global limit")
    void aShowMayRaiseItsOwnLimit() {
        setConfig("reservation.per_user_limit", "2");

        ShowResponse show = api.createShowRaw(api.adminToken("root"),
                new com.amitdubey.seats.show.dto.CreateShowRequest(
                        "premium", labels(10), 500L, 5),
                ShowResponse.class).getBody();
        String token = api.userToken("vip");

        for (int i = 1; i <= 5; i++) {
            assertThat(api.reserve(token, show.id().toString(), List.of("A" + i), "p" + i,
                    String.class).getStatusCode().value())
                    .as("the show's own limit of 5 governs, not the global 2")
                    .isEqualTo(201);
        }
        assertThat(api.reserve(token, show.id().toString(), List.of("A6"), "p6", String.class)
                .getStatusCode().value()).isEqualTo(409);
    }
}
