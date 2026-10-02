package com.amitdubey.seats.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link Seat}. Field names must match the {@code @Id} fields on the
 * entity.
 */
public class SeatId implements Serializable {

    private UUID showId;
    private String label;

    protected SeatId() {
    }

    public SeatId(UUID showId, String label) {
        this.showId = showId;
        this.label = label;
    }

    public UUID getShowId() {
        return showId;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof SeatId other
                && Objects.equals(showId, other.showId)
                && Objects.equals(label, other.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(showId, label);
    }
}
