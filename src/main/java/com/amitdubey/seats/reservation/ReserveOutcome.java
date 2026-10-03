package com.amitdubey.seats.reservation;

import com.amitdubey.seats.reservation.dto.ReservationResponse;

/**
 * A reservation result, plus whether it is new.
 *
 * <p>The distinction exists because only the service can know it. A replay returns a booking
 * that looks identical to a fresh one — same seats, same amount, same {@code confirmed}
 * status — so a controller trying to work it out by comparing bodies would get it wrong. This
 * carries the one bit of information needed to answer 201 versus 200.
 *
 * @param replayed true when an earlier identical request already created this booking
 */
public record ReserveOutcome(ReservationResponse response, boolean replayed) {

    static ReserveOutcome created(ReservationResponse response) {
        return new ReserveOutcome(response, false);
    }

    static ReserveOutcome replayed(ReservationResponse response) {
        return new ReserveOutcome(response, true);
    }
}
