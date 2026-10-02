package com.amitdubey.seats.entity;

/**
 * The only two states a seat can be in.
 *
 * <p>There is deliberately no {@code HELD}: the spec allows either explicit cancellation
 * or time-boxed holds, and we chose cancellation. That removes a state, a background
 * sweeper, and a clock to reason about.
 */
public enum SeatStatus {
    AVAILABLE,
    CONFIRMED
}
