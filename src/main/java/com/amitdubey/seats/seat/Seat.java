package com.amitdubey.seats.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One seat in one show. <strong>The source of truth for who owns what.</strong>
 *
 * <p>Two things about this class are deliberate and load-bearing:
 *
 * <p><strong>Seat rows are never inserted outside show creation.</strong> Every seat is
 * written once, when its show is created, and after that only ever updated. No code path
 * inserts a seat, so a duplicate seat is not prevented — it cannot be written down.
 *
 * <p><strong>This entity is never used to write a seat.</strong> Acquisition and release
 * both go through native statements in {@code SeatRepository} that return a
 * rows-affected count, because the whole correctness argument rests on exactly which SQL
 * runs and on reading that count. Letting Hibernate's dirty checking decide when and how
 * a seat is updated would put a cache between us and the row lock. This class exists to
 * *read* seats and to let Hibernate validate the mapping against the schema.
 *
 * <p>Related rows are plain {@code UUID} columns rather than {@code @ManyToOne}
 * associations. We never need the object graph here, and associations would invite lazy
 * loading on the hottest path in the service.
 */
@Entity
@Table(name = "seats")
@IdClass(SeatId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Seat {

    @Id
    @Column(name = "show_id")
    private UUID showId;

    @Id
    @Column(name = "label")
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    /** Who holds it. Null exactly when the seat is available. */
    @Column(name = "owner_id")
    private UUID ownerId;

    /** Which booking owns it. Null exactly when the seat is available. */
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Seat(UUID showId, String label) {
        this.showId = showId;
        this.label = label;
        this.status = SeatStatus.AVAILABLE;
        this.updatedAt = Instant.now();
    }
}
