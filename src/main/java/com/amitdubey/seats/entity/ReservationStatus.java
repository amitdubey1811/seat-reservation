package com.amitdubey.seats.entity;

/** A booking is live or it has been cancelled. Cancelled rows are kept, never deleted. */
public enum ReservationStatus {
    CONFIRMED,
    CANCELLED
}
