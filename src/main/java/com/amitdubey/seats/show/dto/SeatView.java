package com.amitdubey.seats.show.dto;

/**
 * One seat in the show-state response.
 *
 * <p>{@code status} is a lowercase string rather than the enum, because the specification
 * describes the states as {@code available / held / confirmed} and matching that exactly
 * costs nothing.
 */
public record SeatView(String label, String status) {
}
