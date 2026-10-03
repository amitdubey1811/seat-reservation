package com.amitdubey.seats.reservation.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code { "reservation_id": …, "show_id": …, "user_id": …, "seats": [...],
 * "amount_paise": 25000, "status": "confirmed" }}
 *
 * @param userId       always the token's user, never anything the request body claimed
 * @param amountPaise  integer minor units, computed server-side as price × seat count. The
 *                     client never sends an amount
 * @param status       lowercase, matching the specification's wording
 */
public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        UUID userId,
        List<String> seats,
        long amountPaise,
        String status) {
}
