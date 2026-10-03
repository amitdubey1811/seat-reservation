package com.amitdubey.seats.seat;

import java.util.UUID;

/** Per-show seat counts, for the metrics gauges. */
public interface ShowSeatCountsView {

    UUID getShowId();

    String getShowName();

    long getTotalSeats();

    long getAvailable();

    long getConfirmed();
}
