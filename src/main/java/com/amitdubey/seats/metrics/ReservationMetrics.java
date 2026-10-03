package com.amitdubey.seats.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * What a burst looks like from the outside.
 *
 * <p>Declines are counted <em>by reason</em>, which is the difference between "lots of 409s"
 * and knowing whether buyers are losing seat races, hitting their limit, or retrying. During
 * an on-sale those tell very different stories and call for very different responses.
 *
 * <p>{@code idempotent_replay} is counted as a decline reason even though the caller gets a
 * success: no new booking was created, and that is the fact worth measuring.
 */
@Component
public class ReservationMetrics {

    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String IDEMPOTENCY_KEY_REUSE = "idempotency_key_reuse";
    public static final String SEAT_UNKNOWN = "seat_unknown";

    private final MeterRegistry meters;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Counter seatsConfirmed;
    private final Counter backstopFired;
    private final Map<String, Counter> declines = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.confirmed = Counter.builder("reservations_confirmed_total")
                .description("Reservations confirmed.")
                .register(meters);
        this.cancelled = Counter.builder("reservations_cancelled_total")
                .description("Reservations cancelled by their owner.")
                .register(meters);
        this.seatsConfirmed = Counter.builder("seats_confirmed_total")
                .description("Individual seats confirmed. Differs from the reservation count "
                        + "whenever a booking covers more than one seat.")
                .register(meters);
        this.backstopFired = Counter.builder("seat_backstop_fired_total")
                .description("Times the unique index refused a seat the conditional update "
                        + "believed it had won. MUST STAY ZERO: a non-zero value means the "
                        + "primary acquisition logic is wrong and only the database is "
                        + "preventing a double-sell. This is the 2am page.")
                .register(meters);
    }

    public void confirmed(int seatCount) {
        confirmed.increment();
        seatsConfirmed.increment(seatCount);
    }

    public void cancelled() {
        cancelled.increment();
    }

    /**
     * The database refused a seat the conditional update thought it had taken.
     *
     * <p>Separate from the ordinary decline counters on purpose. A {@code seat_taken} decline
     * is business as usual; this is a correctness alarm, and burying it among thousands of
     * normal conflicts would hide the one number that says the core logic has broken.
     */
    public void backstopFired() {
        backstopFired.increment();
    }

    public void declined(String reason) {
        declines.computeIfAbsent(reason, r -> Counter.builder("reservations_declined_total")
                .description("Reservations declined, by reason.")
                .tag("reason", r)
                .register(meters)).increment();
    }
}
