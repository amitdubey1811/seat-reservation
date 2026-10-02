package com.amitdubey.seats.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.show.dto.SeatCounts;
import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ShowStateTest extends IntegrationTest {

    @Test
    void reportsEverySeatAndTheCountsAgreeWithIt() {
        ShowResponse created = api.createShow("state", List.of("A1", "A2", "A3", "A4"), 500L);

        ShowResponse show = api.getShow(created.id().toString()).getBody();

        assertThat(show).isNotNull();
        assertThat(show.seats()).hasSize(4);
        assertThat(show.counts().available()).isEqualTo(4);
        assertThat(show.counts().totalSeats()).isEqualTo(4);
    }

    @Test
    void theReconciliationInvariantHolds() {
        ShowResponse created = api.createShow("invariant", List.of("A1", "A2", "A3"), 500L);

        SeatCounts counts = api.getShow(created.id().toString()).getBody().counts();

        assertThat(counts.available() + counts.held() + counts.confirmed())
                .as("available + held + confirmed must equal total_seats")
                .isEqualTo(counts.totalSeats());
        assertThat(counts.reconciled()).isTrue();
    }

    @Test
    void countsOnlyAgreesWithTheFullListing() {
        ShowResponse created = api.createShow("modes", List.of("A1", "A2", "A3"), 500L);
        String id = created.id().toString();

        SeatCounts withSeats = api.getShow(id).getBody().counts();
        SeatCounts countsOnly = api.getShowCountsOnly(id).getBody().counts();

        assertThat(countsOnly)
                .as("one path counts in Java from the rows, the other with a SQL aggregate; "
                        + "they must never disagree")
                .isEqualTo(withSeats);
    }

    @Test
    void countsOnlyOmitsTheSeatList() {
        ShowResponse created = api.createShow("omit", List.of("A1", "A2"), 500L);

        ShowResponse countsOnly = api.getShowCountsOnly(created.id().toString()).getBody();

        assertThat(countsOnly.seats())
                .as("a 20,000-seat hall should not be serialised when only counts were asked for")
                .isNull();
        assertThat(countsOnly.counts().totalSeats()).isEqualTo(2);
    }

    @Test
    void heldIsAlwaysZeroBecauseWeChoseExplicitCancellation() {
        ShowResponse created = api.createShow("held", List.of("A1"), 500L);

        assertThat(api.getShow(created.id().toString()).getBody().counts().held()).isZero();
    }

    @Test
    void readingAShowNeedsNoToken() {
        ShowResponse created = api.createShow("public", List.of("A1"), 500L);

        ResponseEntity<ShowResponse> response = api.getShow(created.id().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownShowIsNotFound() {
        ResponseEntity<String> response =
                http.getForEntity("/shows/" + UUID.randomUUID(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("show_not_found");
    }

    @Test
    void aMalformedShowIdIsABadRequestNotAServerError() {
        ResponseEntity<String> response = http.getForEntity("/shows/not-a-uuid", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getStatusCode().is5xxServerError()).isFalse();
    }

    @Test
    void seatsAreListedInLabelOrderRegardlessOfInputOrder() {
        ShowResponse created = api.createShow("ordering", List.of("A9", "A1", "A5"), 500L);

        assertThat(api.getShow(created.id().toString()).getBody().seats())
                .extracting("label")
                .containsExactly("A1", "A5", "A9");
    }
}
