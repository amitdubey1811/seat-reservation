package com.amitdubey.seats.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key for {@link ReservationSeat}. */
public class ReservationSeatId implements Serializable {

    private UUID reservationId;
    private String label;

    protected ReservationSeatId() {
    }

    public ReservationSeatId(UUID reservationId, String label) {
        this.reservationId = reservationId;
        this.label = label;
    }

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
