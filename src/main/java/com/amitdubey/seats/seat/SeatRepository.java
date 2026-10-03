package com.amitdubey.seats.seat;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, SeatId> {

    /**
     * Creates every seat for a show in <strong>one statement</strong>.
     *
     * <p>A 20,000-seat hall must not be 20,000 round trips, and it must not be 20,000
     * managed entities either. Expanding a delimited list server-side with
     * {@code unnest(string_to_array(...))} inserts the lot in a single round trip and
     * never materialises an entity.
     *
     * <p>The comma delimiter is safe because seat labels are validated against a charset
     * that excludes it — see {@code CreateShowRequest}. That validation is load-bearing,
     * not cosmetic.
     *
     * @return how many rows were inserted; the caller checks this against what it asked for
     */
    @Modifying
    @Query(value = """
            INSERT INTO seats (show_id, label, status, updated_at)
            SELECT :showId, label, 'AVAILABLE', now()
              FROM unnest(string_to_array(:labels, ',')) AS label
            """, nativeQuery = true)
    int insertSeats(@Param("showId") UUID showId, @Param("labels") String commaSeparatedLabels);

    /**
     * <strong>The statement the whole service turns on.</strong>
     *
     * <p>Takes one seat if and only if it is currently available, and reports how many rows
     * it changed:
     *
     * <ul>
     *   <li>{@code 1} — this caller won the seat</li>
     *   <li>{@code 0} — somebody else has it. <em>Not an error.</em> There is no exception
     *       to catch and nothing to retry, which is precisely why contention in this service
     *       can never surface as a 5xx</li>
     * </ul>
     *
     * <p>Race-free at plain READ COMMITTED without any explicit locking, because Postgres
     * takes the row lock as part of performing the update. A second transaction waits, then
     * re-evaluates the {@code WHERE} clause against the committed row, finds the seat is no
     * longer {@code AVAILABLE}, and changes nothing. There is no window between checking and
     * taking, because they are the same operation.
     *
     * <p>Native, and never an entity write. Routing this through Hibernate would put
     * dirty-checking and flush ordering between us and the row lock, and the correctness
     * argument depends on exactly this SQL running at exactly this moment.
     *
     * <p>{@code flushAutomatically} matters: the caller inserts the reservation row through
     * JPA immediately before this runs, and the foreign key on {@code reservation_id} needs
     * that row to be in the database already. {@code clearAutomatically} is deliberately
     * left off — it would evict the whole persistence context, detaching the very
     * reservation the caller is still working with.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status         = 'CONFIRMED',
                   owner_id       = :userId,
                   reservation_id = :reservationId,
                   updated_at     = now()
             WHERE show_id = :showId
               AND label   = :label
               AND status  = 'AVAILABLE'
            """, nativeQuery = true)
    int acquire(@Param("showId") UUID showId,
                @Param("label") String label,
                @Param("userId") UUID userId,
                @Param("reservationId") UUID reservationId);

    /**
     * Returns one seat to the pool, but only if it still belongs to this reservation.
     *
     * <p>The {@code reservation_id = :reservationId} predicate is the entire safety argument
     * for cancellation: a release can only ever touch a seat its own booking still owns, so
     * it is structurally incapable of freeing a seat that has since been confirmed to
     * somebody else.
     *
     * @return 1 if the seat was released, 0 if it was no longer ours to release
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE seats
               SET status         = 'AVAILABLE',
                   owner_id       = NULL,
                   reservation_id = NULL,
                   updated_at     = now()
             WHERE show_id        = :showId
               AND label          = :label
               AND reservation_id = :reservationId
            """, nativeQuery = true)
    int release(@Param("showId") UUID showId,
                @Param("label") String label,
                @Param("reservationId") UUID reservationId);

    /**
     * How many seats this user already holds for this show.
     *
     * <p>Only meaningful while the caller holds that user's row in {@code user_show_locks};
     * without it, ten concurrent requests would each read the same number and each conclude
     * they were under the limit.
     */
    @Query(value = """
            SELECT count(*)
              FROM seats
             WHERE show_id  = :showId
               AND owner_id = :userId
               AND status   = 'CONFIRMED'
            """, nativeQuery = true)
    long countOwnedBy(@Param("showId") UUID showId, @Param("userId") UUID userId);

    /**
     * Distinguishes "that seat is taken" from "there is no such seat".
     *
     * <p>Only ever called after {@link #acquire} returned zero, so the happy path stays a
     * single statement. Without it a request for a seat that does not exist would be
     * reported as a 409 conflict rather than a 404.
     */
    boolean existsByShowIdAndLabel(UUID showId, String label);

    /** Every seat's status, ordered by label. Counts are derived from this same list. */
    @Query("""
            select s.label as label, s.status as status
              from Seat s
             where s.showId = :showId
             order by s.label
            """)
    List<SeatStatusView> findStatusesByShow(@Param("showId") UUID showId);

    /**
     * The counts, from a <strong>single</strong> statement.
     *
     * <p>Three separate {@code count(*)} queries could each observe a different moment
     * mid-burst and make a perfectly correct service look as though it had broken its
     * reconciliation invariant. One statement sees one snapshot.
     */
    @Query(value = """
            SELECT count(*) FILTER (WHERE status = 'AVAILABLE') AS available,
                   count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                   count(*)                                    AS total
              FROM seats
             WHERE show_id = :showId
            """, nativeQuery = true)
    SeatCountView countByShow(@Param("showId") UUID showId);
}
