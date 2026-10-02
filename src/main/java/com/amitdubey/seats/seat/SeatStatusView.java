package com.amitdubey.seats.seat;

/**
 * A seat reduced to the two fields the show-state endpoint needs.
 *
 * <p>A projection rather than the entity: a full hall can be tens of thousands of seats,
 * and loading that many managed entities to read two columns each would put all of them in
 * the persistence context for no reason.
 */
public interface SeatStatusView {

    String getLabel();

    SeatStatus getStatus();
}
