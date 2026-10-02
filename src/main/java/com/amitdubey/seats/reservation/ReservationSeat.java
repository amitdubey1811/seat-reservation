package com.amitdubey.seats.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Which seat belonged to which booking, including bookings that have been cancelled.
 *
 * <p>{@code releasedAt} is the important column. A unique index on
 * {@code (show_id, label) WHERE released_at IS NULL} allows only one <em>live</em> claim
 * per seat. That is not "one booking per seat ever" — cancelling sets
 * {@code releasedAt}, the row drops out of the index, the seat becomes bookable again,
 * and this row stays as history.
 *
 * <p>The index is defense in depth. The conditional update in {@code SeatRepository}
 * already makes a double-sell impossible; this means that even if that were wrong,
 * Postgres refuses the second live claim and the caller gets a clean 409.
 */
@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeatId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ReservationSeat {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Id
    @Column(name = "label")
    private String label;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    /** Null means this claim is live. */
    @Column(name = "released_at")
    private Instant releasedAt;

    public ReservationSeat(UUID reservationId, UUID showId, String label) {
        this.reservationId = reservationId;
        this.showId = showId;
        this.label = label;
    }

    public boolean isLive() {
        return releasedAt == null;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public String getLabel() {
        return label;
    }

    public UUID getShowId() {
        return showId;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
