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
