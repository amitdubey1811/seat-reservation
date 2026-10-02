package com.amitdubey.seats.show;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * An event with a fixed set of numbered seats.
 *
 * <p>{@code pricePaise} is integer minor units — never a float, never a BigDecimal.
 * ₹250 is {@code 25000}.
 */
@Entity
@Table(name = "shows")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Show {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    /** {@code null} means "use the global limit from service_config". */
    @Column(name = "per_user_limit")
    private Integer perUserLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Show(UUID id, String name, long pricePaise, int totalSeats, Integer perUserLimit) {
        this.id = id;
        this.name = name;
        this.pricePaise = pricePaise;
        this.totalSeats = totalSeats;
        this.perUserLimit = perUserLimit;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public Integer getPerUserLimit() {
        return perUserLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
