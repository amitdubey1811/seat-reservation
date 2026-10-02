package com.amitdubey.seats.reservation;

/** A booking is live or it has been cancelled. Cancelled rows are kept, never deleted. */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED
}
