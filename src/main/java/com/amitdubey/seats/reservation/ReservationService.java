package com.amitdubey.seats.reservation;

import com.amitdubey.seats.auth.AuthenticatedUser;
import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.exception.PgErrors;
import com.amitdubey.seats.idempotency.IdempotencyKey;
import com.amitdubey.seats.idempotency.IdempotencyKeyId;
import com.amitdubey.seats.idempotency.IdempotencyKeyRepository;
import com.amitdubey.seats.metrics.ReservationMetrics;
import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.seat.SeatRepository;
import com.amitdubey.seats.serviceconfig.ServiceConfigService;
import com.amitdubey.seats.show.Show;
import com.amitdubey.seats.show.ShowRepository;
import com.amitdubey.seats.usershowlock.UserShowLockRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserving and cancelling seats.
 *
 * <p>{@link #reserve} is the only part of this service that has to be right under load, and
 * it is written to be read in order: each step says what it does and why it sits where it
 * does. The lock order it follows is global and acyclic —
 *
 * <pre>
 *   idempotency key  →  user lock  →  seats (ascending by label)
 *      (per user)        (per user)         (shared)
 * </pre>
 *
 * <p>The first two are per-user, so two different buyers can only ever contend on seat rows,
 * and they always climb those in the same direction. A deadlock is therefore not merely
 * unlikely here; it cannot be constructed.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    /**
     * How long a statement will wait for a row lock before giving up.
     *
     * <p>Set per transaction rather than on the connection, because a pooler in transaction
     * mode will not preserve session-level settings. Without it, a pathological wait holds a
     * connection indefinitely; with it, the wait becomes a clean 429.
     */
    private static final String LOCK_TIMEOUT = "1000ms";

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final UserShowLockRepository userShowLocks;
    private final ServiceConfigService config;
    private final ReservationMetrics metrics;
    private final EntityManager entityManager;

    public ReservationService(ShowRepository shows,
                             SeatRepository seats,
                             ReservationRepository reservations,
                             ReservationSeatRepository reservationSeats,
                             IdempotencyKeyRepository idempotencyKeys,
                             UserShowLockRepository userShowLocks,
                             ServiceConfigService config,
                             ReservationMetrics metrics,
                             EntityManager entityManager) {
        this.shows = shows;
        this.seats = seats;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.idempotencyKeys = idempotencyKeys;
        this.userShowLocks = userShowLocks;
        this.config = config;
        this.metrics = metrics;
        this.entityManager = entityManager;
    }

    /**
     * Takes the requested seats, all of them or none.
     *
     * <p>Every decline rolls the whole transaction back, so there is nothing half-written and
     * no compensating logic anywhere in this class. A seat is either confirmed to this
     * booking when the transaction commits, or it was never touched.
     */
    @Transactional
    public ReserveOutcome reserve(AuthenticatedUser user, UUID showId,
                                  List<String> requestedSeats, String idempotencyKey) {

        // --- before any SQL ------------------------------------------------------------
        List<String> labels = sortedDistinct(requestedSeats);
        int maxPerRequest = config.snapshot().maxSeatsPerRequest();
        if (labels.size() > maxPerRequest) {
            throw ApiError.TOO_MANY_SEATS.asException(
                    "At most " + maxPerRequest + " seats may be reserved in one call.");
        }
        String requestHash = hash(showId, labels);

        setLockTimeout();

        // --- 1. claim the idempotency key, before anything else ------------------------
        if (idempotencyKeys.insertIfAbsent(user.id(), idempotencyKey, requestHash) == 0) {
            return replay(user, idempotencyKey, requestHash);
        }

        // --- 2 & 3. serialise this user's own concurrent requests ----------------------
        userShowLocks.insertIfAbsent(user.id(), showId);
        userShowLocks.lockForUpdate(user.id(), showId);

        // --- 4. the show --------------------------------------------------------------
        Show show = shows.findById(showId).orElseThrow(() ->
                ApiError.SHOW_NOT_FOUND.asException("No show with id " + showId));
        int limit = show.getPerUserLimit() != null
                ? show.getPerUserLimit()
                : config.snapshot().perUserLimit();

        // --- 5. the limit, counted from the seat table itself --------------------------
        // Safe to count now, and only now: step 3 guarantees no sibling request for this
        // user is between its own count and its own writes.
        long alreadyOwned = seats.countOwnedBy(showId, user.id());
        if (alreadyOwned + labels.size() > limit) {
            metrics.declined(ReservationMetrics.PER_USER_LIMIT);
            throw ApiError.PER_USER_LIMIT.asException(
                    "You hold " + alreadyOwned + " of " + limit + " seats for this show and "
                            + "asked for " + labels.size() + " more.");
        }

        // --- 6. the booking, before the seats that point at it ------------------------
        UUID reservationId = UUID.randomUUID();
        long amountPaise = show.getPricePaise() * labels.size();
        reservations.saveAndFlush(
                new Reservation(reservationId, showId, user.id(), amountPaise, labels.size()));

        // --- 7. the seats, one statement each, in sorted order ------------------------
        for (String label : labels) {
            if (seats.acquire(showId, label, user.id(), reservationId) == 0) {
                // Zero rows has two possible meanings; tell them apart so a request for a
                // seat that does not exist is a 404 rather than a confusing conflict.
                if (!seats.existsByShowIdAndLabel(showId, label)) {
                    metrics.declined(ReservationMetrics.SEAT_UNKNOWN);
                    throw ApiError.SEAT_UNKNOWN.asException(
                            "Show " + showId + " has no seat " + label + ".");
                }
                metrics.declined(ReservationMetrics.SEAT_TAKEN);
                throw ApiError.SEAT_TAKEN.asException("Seat " + label + " is already taken.");
            }
        }

        // --- 8 & 9. record the seats, then point the key at the booking ---------------
        try {
            reservationSeats.insertAll(reservationId, showId, String.join(",", labels));
        } catch (DataIntegrityViolationException e) {
            // Reaching here means the partial unique index refused a second live claim on a
            // seat that step 7 believed it had won — which can only happen if the conditional
            // update above is wrong. The backstop has done its job and no seat has been sold
            // twice; what matters now is that the caller gets the same clean decline they
            // would have got anyway, rather than a 500.
            //
            // Catching this marks the transaction rollback-only, which is exactly what we
            // want: we throw immediately and touch the database no further.
            if (PgErrors.isUniqueViolation(e)) {
                log.error("seat backstop fired for show={} seats={} — the conditional update "
                        + "let through a seat it should not have", showId, labels, e);
                metrics.backstopFired();
                metrics.declined(ReservationMetrics.SEAT_TAKEN);
                throw ApiError.SEAT_TAKEN.asException("One of those seats is already taken.");
            }
            throw e;
        }
        idempotencyKeys.attachReservation(user.id(), idempotencyKey, reservationId);

        metrics.confirmed(labels.size());
        log.info("confirmed reservation={} show={} user={} seats={} amount_paise={}",
                reservationId, showId, user.id(), labels, amountPaise);

        return ReserveOutcome.created(new ReservationResponse(reservationId, showId,
                user.id(), labels, amountPaise, "confirmed"));
    }

    /**
     * Gives a booking's seats back.
     *
     * <p>Idempotent, and incapable of touching a seat that is not ours — see the predicate on
     * {@link SeatRepository#release}.
     */
    @Transactional
    public ReservationResponse cancel(AuthenticatedUser user, UUID reservationId) {
        setLockTimeout();

        Reservation reservation = reservations.findByIdForUpdate(reservationId)
                .filter(r -> r.isOwnedBy(user.id()))
                .orElseThrow(() -> ApiError.RESERVATION_NOT_FOUND.asException(
                        "No reservation with id " + reservationId + "."));
        // Note the 404 above rather than a 403 for someone else's booking: a 403 would
        // confirm that the id exists, which is not something a stranger needs to learn.

        List<ReservationSeat> claims =
                reservationSeats.findByReservationIdOrderByLabel(reservationId);
        List<String> labels = claims.stream().map(ReservationSeat::getLabel).toList();

        if (reservation.isCancelled()) {
            return new ReservationResponse(reservationId, reservation.getShowId(),
                    reservation.getUserId(), labels, reservation.getAmountPaise(), "cancelled");
        }

        // Sorted by label, the same direction reserve takes them. Reserve and cancel cannot
        // actually deadlock — their WHERE predicates are disjoint, so neither waits on the
        // other's rows — but "seat locks are always taken in ascending label order" is a far
        // easier rule to keep than that argument is to re-derive.
        for (ReservationSeat claim : claims) {
            seats.release(reservation.getShowId(), claim.getLabel(), reservationId);
        }
        reservationSeats.releaseAll(reservationId, Instant.now());

        reservation.cancel();
        reservations.saveAndFlush(reservation);

        metrics.cancelled();
        log.info("cancelled reservation={} show={} user={} seats={}",
                reservationId, reservation.getShowId(), user.id(), labels);

        return new ReservationResponse(reservationId, reservation.getShowId(),
                reservation.getUserId(), labels, reservation.getAmountPaise(), "cancelled");
    }

    /** A booking, for its owner only. */
    @Transactional(readOnly = true)
    public ReservationResponse find(AuthenticatedUser user, UUID reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .filter(r -> r.isOwnedBy(user.id()))
                .orElseThrow(() -> ApiError.RESERVATION_NOT_FOUND.asException(
                        "No reservation with id " + reservationId + "."));
        return toResponse(reservation);
    }

    // --- internals ---------------------------------------------------------------------

    /**
     * Returns the booking an earlier identical request already produced.
     *
     * <p>Reached when the key insert reported zero rows, which means some other request owns
     * this key. If it was for a different set of seats the client has a bug and we will not
     * guess which request it meant.
     */
    private ReserveOutcome replay(AuthenticatedUser user, String idempotencyKey,
                                  String requestHash) {
        IdempotencyKey existing = idempotencyKeys
                .findById(new IdempotencyKeyId(user.id(), idempotencyKey))
                .orElseThrow(() -> ApiError.BUSY.asException(
                        "A concurrent request is still resolving this idempotency key."));

        if (!existing.matches(requestHash)) {
            metrics.declined(ReservationMetrics.IDEMPOTENCY_KEY_REUSE);
            throw ApiError.IDEMPOTENCY_KEY_REUSE.asException(
                    "This idempotency key was already used for a different set of seats.");
        }

        if (existing.getReservationId() == null) {
            // The key exists but points at nothing. The only way to see this is a crash
            // between the two writes of a single transaction, which cannot happen — so treat
            // it as transient rather than inventing a permanent failure.
            throw ApiError.BUSY.asException(
                    "A concurrent request is still resolving this idempotency key.");
        }

        metrics.declined(ReservationMetrics.IDEMPOTENT_REPLAY);
        Reservation reservation = reservations.findById(existing.getReservationId())
                .orElseThrow(() -> ApiError.RESERVATION_NOT_FOUND.asException(
                        "The reservation this key refers to no longer exists."));

        log.info("replayed idempotency key for reservation={} user={}",
                reservation.getId(), user.id());
        return ReserveOutcome.replayed(toResponse(reservation));
    }

    private ReservationResponse toResponse(Reservation reservation) {
        List<String> labels = reservationSeats
                .findByReservationIdOrderByLabel(reservation.getId()).stream()
                .map(ReservationSeat::getLabel)
                .toList();
        return new ReservationResponse(reservation.getId(), reservation.getShowId(),
                reservation.getUserId(), labels, reservation.getAmountPaise(),
                reservation.getStatus().name().toLowerCase(Locale.ROOT));
    }

    private void setLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'")
                .executeUpdate();
    }

    /**
     * Sorts and rejects repeats.
     *
     * <p>Sorting is not cosmetic — it is what makes the acquisition order deterministic
     * across every request in the service, and therefore what rules out a lock cycle.
     */
    private List<String> sortedDistinct(List<String> requested) {
        List<String> duplicates = new ArrayList<>();
        LinkedHashSet<String> unique = new LinkedHashSet<>(requested.size());
        for (String label : requested) {
            if (!unique.add(label)) {
                duplicates.add(label);
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiError.DUPLICATE_SEATS.asException(
                    "The same seat was listed more than once: " + duplicates.stream().distinct().toList());
        }
        return unique.stream().sorted().toList();
    }

    /**
     * Fingerprints the request so a retry can be told apart from a different request wearing
     * the same key. Over the show and the <em>sorted</em> labels, so {@code [A1,A2]} and
     * {@code [A2,A1]} are correctly recognised as the same request.
     */
    private static String hash(UUID showId, List<String> sortedLabels) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(showId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(String.join(",", sortedLabels).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
