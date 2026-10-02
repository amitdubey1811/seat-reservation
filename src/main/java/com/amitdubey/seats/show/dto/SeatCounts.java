package com.amitdubey.seats.show.dto;

/**
 * The reconciliation invariant, reported rather than asserted.
 *
 * <p>{@code available + held + confirmed} must always equal {@code totalSeats}.
 * {@code reconciled} states whether it currently does, so anyone watching a burst can see
 * the invariant holding without doing the arithmetic themselves — and so a violation shows
 * up as a visible {@code false} rather than something a reader has to notice.
 *
 * <p>{@code held} is always zero. We chose explicit cancellation over timed holds, so no
 * seat is ever in that state; the field exists because the specification asks for three
 * numbers and zero is the honest answer for this model.
 */
public record SeatCounts(long available, long held, long confirmed, long totalSeats,
                         boolean reconciled) {

    public static SeatCounts of(long available, long confirmed, long totalSeats) {
        long held = 0L;
        return new SeatCounts(available, held, confirmed, totalSeats,
                available + held + confirmed == totalSeats);
    }
}
