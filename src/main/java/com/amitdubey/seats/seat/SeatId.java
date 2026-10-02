package com.amitdubey.seats.seat;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Composite key for {@link Seat}. Field names must match the {@code @Id} fields on the
 * entity.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class SeatId implements Serializable {

    private UUID showId;
    private String label;

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
