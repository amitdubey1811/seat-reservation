package com.amitdubey.seats.seat;

/** The three counts, produced by a single aggregate statement. */
public interface SeatCountView {

    long getAvailable();

    long getConfirmed();

    long getTotal();
}
