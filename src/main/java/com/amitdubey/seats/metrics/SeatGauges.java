package com.amitdubey.seats.metrics;

import com.amitdubey.seats.seat.SeatRepository;
import com.amitdubey.seats.seat.ShowSeatCountsView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes seat availability as Prometheus gauges.
 *
 * <p><strong>Every number here is read from the database, never accumulated in memory.</strong>
 * An in-memory counter is the obvious implementation and the wrong one: it drifts the moment
 * a process restarts or a transaction rolls back, and then the metrics endpoint quietly
 * disagrees with {@code GET /shows/{id}}. Since the two reconciling is a stated requirement,
 * the only way to guarantee it is to have both read the same source.
 *
 * <p>The cost of that choice is staleness bounded by the refresh interval — a gauge can lag
 * the API by up to a second mid-burst. That is the honest trade, and it is the right way
 * round: briefly behind is recoverable, permanently wrong is not.
 *
 * <p>Both the declared seat total and the live counts are published, so the reconciliation
 * invariant can be checked from the metrics endpoint alone:
 *
 * <pre>
 *   seats_available{show_id=…} + show_seats_confirmed{show_id=…}
 *       == show_seats_capacity{show_id=…}
 * </pre>
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    private final SeatRepository seats;
    private final int showLimit;

    private final MultiGauge available;
    private final MultiGauge confirmed;
    private final MultiGauge total;
    private final Counter mismatches;
    private final Counter refreshFailures;

    private volatile boolean shuttingDown;

    public SeatGauges(SeatRepository seats,
                      MeterRegistry meters,
                      @Value("${seats.metrics.show-limit:50}") int showLimit) {
        this.seats = seats;
        this.showLimit = showLimit;

        this.available = MultiGauge.builder("seats_available")
                .description("Seats currently available, per show. Read from the database.")
                .register(meters);
        // Not "seats_confirmed": Prometheus treats _total as the counter suffix, so that
        // name collides with the seats_confirmed_total counter's base name and the gauge is
        // dropped silently — no error, it simply never appears. And not "seats_total"
        // either: Micrometer strips the _total suffix from a gauge, leaving it named
        // "seats". Both were caught by the metrics failing to reconcile with the API.
        this.confirmed = MultiGauge.builder("show_seats_confirmed")
                .description("Seats currently confirmed, per show. Read from the database.")
                .register(meters);
        this.total = MultiGauge.builder("show_seats_capacity")
                .description("Seats the show was created with, per show.")
                .register(meters);

        this.mismatches = Counter.builder("reconciliation_mismatch_total")
                .description("Times available + confirmed did not equal the show's declared "
                        + "seat count. MUST STAY ZERO: a non-zero value means seats have been "
                        + "lost or duplicated and the invariant is broken.")
                .register(meters);
        this.refreshFailures = Counter.builder("seat_gauge_refresh_failed_total")
                .description("Failed gauge refreshes. Non-zero means the published seat "
                        + "numbers are stale and should not be trusted.")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${seats.metrics.refresh-interval-ms:1000}")
    public void refresh() {
        if (shuttingDown) {
            return;
        }
        try {
            publish(load());
        } catch (RuntimeException e) {
            refreshFailures.increment();
            // Debug, not error: the database being briefly unreachable is already reported
            // by the readiness probe, and shouting about it here would just add noise to an
            // incident that is already visible.
            log.debug("seat gauge refresh failed; gauges are stale", e);
        }
    }

    @Transactional(readOnly = true)
    protected List<ShowSeatCountsView> load() {
        return seats.countsByShow(showLimit);
    }

    private void publish(List<ShowSeatCountsView> rows) {
        available.register(rows.stream()
                .map(r -> MultiGauge.Row.of(tagsFor(r), r.getAvailable()))
                .toList(), true);
        confirmed.register(rows.stream()
                .map(r -> MultiGauge.Row.of(tagsFor(r), r.getConfirmed()))
                .toList(), true);
        total.register(rows.stream()
                .map(r -> MultiGauge.Row.of(tagsFor(r), r.getTotalSeats()))
                .toList(), true);

        for (ShowSeatCountsView row : rows) {
            if (row.getAvailable() + row.getConfirmed() != row.getTotalSeats()) {
                mismatches.increment();
                log.error("reconciliation broken for show={} ({}): available={} confirmed={} "
                                + "but the show declares {} seats",
                        row.getShowId(), row.getShowName(), row.getAvailable(),
                        row.getConfirmed(), row.getTotalSeats());
            }
        }
    }

    private static Tags tagsFor(ShowSeatCountsView row) {
        return Tags.of("show_id", row.getShowId().toString(), "show", row.getShowName());
    }

    /**
     * Stops the scheduled refresh before the persistence layer closes. Without this the
     * final tick races shutdown and fails against a closed EntityManagerFactory, which would
     * log an error on every clean restart — and a service that cries wolf on every deploy
     * teaches people to stop reading its logs.
     */
    @PreDestroy
    void stop() {
        shuttingDown = true;
    }
}
