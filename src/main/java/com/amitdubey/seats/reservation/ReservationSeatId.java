package com.amitdubey.seats.reservation;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/** Composite key for {@link ReservationSeat}. */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ReservationSeatId implements Serializable {

    private UUID reservationId;
    private String label;

    public UUID getReservationId() {
        return reservationId;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ReservationSeatId other
                && Objects.equals(reservationId, other.reservationId)
                && Objects.equals(label, other.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, label);
    }
}
