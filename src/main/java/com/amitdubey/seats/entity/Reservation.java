package com.amitdubey.seats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

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

    /** Required by JPA. */
    protected Reservation() {
    }

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

    public UUID getId() {
        return id;
    }

    public UUID getShowId() {
        return showId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public int getSeatCount() {
        return seatCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
