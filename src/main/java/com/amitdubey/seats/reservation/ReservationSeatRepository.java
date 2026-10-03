package com.amitdubey.seats.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationSeatRepository
        extends JpaRepository<ReservationSeat, ReservationSeatId> {

    /**
     * Records every seat of a booking in one statement.
     *
     * <p>A unique index allows only one row per seat with {@code released_at IS NULL}, so if
     * the conditional update in {@code SeatRepository} were ever wrong, this insert would
     * fail with {@code 23505} and the caller would get a clean 409 instead of a seat sold
     * twice. Belt and braces, on purpose.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO reservation_seats (reservation_id, show_id, label)
            SELECT :reservationId, :showId, label
              FROM unnest(string_to_array(:labels, ',')) AS label
            """, nativeQuery = true)
    int insertAll(@Param("reservationId") UUID reservationId,
                  @Param("showId") UUID showId,
                  @Param("labels") String commaSeparatedLabels);

    /**
     * Marks a booking's claims as released, which drops them out of the unique index and
     * makes those seats bookable again. The history rows stay.
     */
    @Modifying
    @Query("""
            update ReservationSeat rs
               set rs.releasedAt = :releasedAt
             where rs.reservationId = :reservationId
               and rs.releasedAt is null
            """)
    int releaseAll(@Param("reservationId") UUID reservationId,
                   @Param("releasedAt") Instant releasedAt);

    /** Ordered by label so cancellation takes seat locks in the same direction as reserve. */
    List<ReservationSeat> findByReservationIdOrderByLabel(UUID reservationId);
}
