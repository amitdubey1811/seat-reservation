package com.amitdubey.seats.show.dto;

import java.util.List;
import java.util.UUID;

/**
 * A show and its current state.
 *
 * @param perUserLimit the <em>effective</em> limit: the show's own override when it has
 *                     one, otherwise the globally configured value. Returning the
 *                     resolved number means a caller never has to know the rule
 * @param seats        omitted when the caller asks for counts only via {@code ?seats=false}
 */
public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int totalSeats,
        int perUserLimit,
        SeatCounts counts,
        List<SeatView> seats) {
}
