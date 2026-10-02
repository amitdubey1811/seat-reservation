package com.amitdubey.seats.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One booking: a user, a show, and the seats recorded in {@link ReservationSeat}.
 *
 * <p>{@code amountPaise} is computed server-side as price times seat count. The client
 * never sends an amount.
 *
 * <p>Rows are never deleted. Cancelling is a status change, so the history survives.
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Reservation {

    @Setter(AccessLevel.NONE)
    @Id
    private UUID id;

    @Setter(AccessLevel.NONE)
    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Setter(AccessLevel.NONE)
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Use {@link #cancel()} to transition this, not a setter. */
    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Reservation(UUID id, UUID showId, UUID userId, long amountPaise, int seatCount) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.status = ReservationStatus.CONFIRMED;
        this.amountPaise = amountPaise;
        this.seatCount = seatCount;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Only the owner may do this; ownership is checked in the service. */
    public void cancel() {
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public boolean isCancelled() {
        return status == ReservationStatus.CANCELLED;
    }

    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }
}
