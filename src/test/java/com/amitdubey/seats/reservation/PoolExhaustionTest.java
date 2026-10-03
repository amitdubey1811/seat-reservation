package com.amitdubey.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.amitdubey.seats.show.dto.ShowResponse;
import com.amitdubey.seats.support.Burst;
import com.amitdubey.seats.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * What happens when there are no database connections left.
 *
 * <p>This test exists because a burst against the deployed service produced 47 server
 * errors that the rest of the suite could never have caught. The cause was a connection
 * pool timing out under load: {@code JpaTransactionManager} wraps that in a
 * {@link org.springframework.transaction.CannotCreateTransactionException}, which extends
 * {@code TransactionException} rather than {@code DataAccessException} — so it slipped past
 * the handler written for precisely that case and became a 500.
 *
 * <p>Nothing else in the suite exhausts a pool, so that path had never once been executed.
 * A deliberately tiny pool and an impatient timeout make the failure reproducible in a
 * second, which is the only way this stays fixed.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=250",
        "seats.metrics.refresh-interval-ms=60000"
})
class PoolExhaustionTest extends IntegrationTest {

    private static List<String> labels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            labels.add("A" + i);
        }
        return labels;
    }

    @Test
    @DisplayName("an exhausted pool sheds load as 429, never as a server error")
    void saturationIsADeclineNotAFailure() {
        ShowResponse show = api.createShow("saturated", labels(80), 500L);
        String showId = show.id().toString();

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            tokens.add(api.userToken("saturate-" + i));
        }

        AtomicInteger next = new AtomicInteger();
        Burst.Result result = Burst.fire(60, () -> {
            int i = next.getAndIncrement();
            ResponseEntity<String> response = api.reserve(
                    tokens.get(i), showId, List.of("A" + (i + 1)), "sat-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        assertThat(result.serverErrors())
                .as("a saturated pool must produce 429s, not 5xx: %s", result)
                .isZero();

        assertThat(result.count(201) + result.count(409) + result.count(429))
                .as("every request resolved as a success, a decline, or honest back-pressure: %s",
                        result)
                .isEqualTo(60);
    }

    @Test
    @DisplayName("whatever is shed, the invariant still holds")
    void sheddingNeverCorruptsState() {
        ShowResponse show = api.createShow("saturated-invariant", labels(40), 500L);
        String showId = show.id().toString();

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tokens.add(api.userToken("inv-" + i));
        }

        AtomicInteger next = new AtomicInteger();
        Burst.fire(40, () -> {
            int i = next.getAndIncrement();
            ResponseEntity<String> response = api.reserve(
                    tokens.get(i), showId, List.of("A" + (i % 10 + 1)), "inv-" + i, String.class);
            return new Burst.Response(response.getStatusCode().value(), response.getBody());
        });

        ShowResponse after = api.getShow(showId).getBody();
        assertThat(after).isNotNull();
        assertThat(after.counts().reconciled())
                .as("requests that were refused a connection must leave no trace")
                .isTrue();
        assertThat(after.counts().available() + after.counts().confirmed())
                .isEqualTo(40L);
    }
}
