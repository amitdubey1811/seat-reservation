package com.amitdubey.seats.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.show.dto.CreateShowRequest;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ShowCreationTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    @Test
    void createsEverySeatInAvailableState() {
        ShowResponse show = api.createShow("friday-night", List.of("A3", "A1", "A2"), 25_000L);

        assertThat(show.totalSeats()).isEqualTo(3);
        assertThat(show.pricePaise()).isEqualTo(25_000L);
        assertThat(show.counts().available()).isEqualTo(3);
        assertThat(show.counts().confirmed()).isZero();
        assertThat(show.counts().reconciled()).isTrue();
        assertThat(show.seats()).extracting("label").containsExactly("A1", "A2", "A3");
        assertThat(show.seats()).extracting("status").containsOnly("available");
    }

    @Test
    void twentyThousandSeatsAreInsertedInOneStatement() {
        long before = countRows("seats");

        ShowResponse show = api.createShow("big-hall", labels(20_000), 25_000L);

        assertThat(show.totalSeats()).isEqualTo(20_000);
        assertThat(countRows("seats") - before).isEqualTo(20_000);
        assertThat(show.counts().available()).isEqualTo(20_000);
        assertThat(show.counts().reconciled()).isTrue();
    }

    @Test
    void aNonAdminTokenCannotCreateAShow() {
        ResponseEntity<String> response = api.createShowRaw(
                api.userToken("alice"),
                new CreateShowRequest("nope", List.of("A1"), 100L, null),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("forbidden");
        assertThat(countRows("shows")).isZero();
    }

    @Test
    void noTokenCannotCreateAShow() {
        ResponseEntity<String> response = api.createShowRaw(
                null, new CreateShowRequest("nope", List.of("A1"), 100L, null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countRows("shows")).isZero();
    }

    @Test
    void duplicateSeatLabelsAreRejectedAsABadRequestNotAConstraintViolation() {
        ResponseEntity<String> response = api.createShowRaw(
                api.adminToken("root"),
                new CreateShowRequest("dup", List.of("A1", "A2", "A1"), 100L, null),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("duplicate_seats");
        assertThat(countRows("shows"))
                .as("a rejected show must leave nothing behind")
                .isZero();
    }

    @Test
    void anEmptySeatListIsRejected() {
        ResponseEntity<String> response = api.createShowRaw(
                api.adminToken("root"), new CreateShowRequest("empty", List.of(), 100L, null),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aNegativePriceIsRejected() {
        ResponseEntity<String> response = api.createShowRaw(
                api.adminToken("root"), new CreateShowRequest("neg", List.of("A1"), -1L, null),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("price_paise");
    }

    @Test
    void aLabelContainingACommaIsRejectedBecauseSeatCreationSplitsOnCommas() {
        ResponseEntity<String> response = api.createShowRaw(
                api.adminToken("root"),
                new CreateShowRequest("comma", List.of("A1,A2"), 100L, null),
                String.class);

        assertThat(response.getStatusCode())
                .as("a comma would split into two seats server-side; this guard is load-bearing")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("seats")).isZero();
    }

    @Test
    void aFractionalPriceIsRefusedRatherThanTruncated() {
        // Money is integer paise. Jackson's default would read 250.75 into a long as 250,
        // silently charging the wrong amount, which is precisely what the rule forbids.
        ResponseEntity<String> response = http.exchange("/shows",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("name", "float", "seats", List.of("A1"), "price_paise", 250.75),
                        api.bearer(api.adminToken("root"))),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("shows")).isZero();
    }

    @Test
    void aPerShowLimitOverridesTheGlobalOne() {
        ResponseEntity<ShowResponse> response = api.createShowRaw(
                api.adminToken("root"),
                new CreateShowRequest("premium", List.of("A1"), 100L, 9),
                ShowResponse.class);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().perUserLimit())
                .as("the response reports the effective limit, so a caller need not know the rule")
                .isEqualTo(9);
    }

    @Test
    void withoutAnOverrideTheGlobalLimitIsReported() {
        ShowResponse show = api.createShow("standard", List.of("A1"), 100L);

        assertThat(show.perUserLimit()).isEqualTo(4);
    }
}
