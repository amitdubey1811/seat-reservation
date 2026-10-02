package com.amitdubey.seats.show;

import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.seat.SeatCountView;
import com.amitdubey.seats.seat.SeatRepository;
import com.amitdubey.seats.seat.SeatStatus;
import com.amitdubey.seats.seat.SeatStatusView;
import com.amitdubey.seats.serviceconfig.ServiceConfigService;
import com.amitdubey.seats.show.dto.CreateShowRequest;
import com.amitdubey.seats.show.dto.SeatCounts;
import com.amitdubey.seats.show.dto.SeatView;
import com.amitdubey.seats.show.dto.ShowResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ServiceConfigService config;

    public ShowService(ShowRepository shows, SeatRepository seats, ServiceConfigService config) {
        this.shows = shows;
        this.seats = seats;
        this.config = config;
    }

    /**
     * Creates a show and all of its seats.
     *
     * <p>Seats are written once, here, and never inserted again anywhere in the service.
     * That is what makes a duplicate seat unrepresentable rather than merely prevented.
     */
    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        List<String> labels = distinctLabelsOrReject(request.seats());

        UUID showId = UUID.randomUUID();
        Show show = new Show(showId, request.name(), request.pricePaise(),
                labels.size(), request.perUserLimit());

        // saveAndFlush, not save: the seat insert below is native SQL and bypasses the
        // persistence context entirely, so the show row has to be in the database before
        // it runs or the foreign key has nothing to point at. Hibernate would otherwise
        // be free to defer this write until commit.
        shows.saveAndFlush(show);

        int inserted = seats.insertSeats(showId, String.join(",", labels));
        if (inserted != labels.size()) {
            // Unreachable unless label validation and the server-side split disagree.
            // Loud rather than silent: a short hall is worse than a failed request.
            throw new IllegalStateException(
                    "expected to insert " + labels.size() + " seats but inserted " + inserted);
        }

        log.info("created show id={} name={} seats={} price_paise={}",
                showId, show.getName(), labels.size(), show.getPricePaise());

        return describe(show, seatViews(labels), SeatCounts.of(labels.size(), 0, labels.size()));
    }

    /**
     * Current state of a show.
     *
     * @param includeSeats when true the per-seat list is returned and the counts are
     *                     derived from that same list, so the two cannot disagree. When
     *                     false a single aggregate statement supplies the counts instead
     */
    @Transactional(readOnly = true)
    public ShowResponse state(UUID showId, boolean includeSeats) {
        Show show = shows.findById(showId).orElseThrow(() ->
                ApiError.SHOW_NOT_FOUND.asException("No show with id " + showId));

        if (!includeSeats) {
            SeatCountView counted = seats.countByShow(showId);
            return describe(show, null,
                    SeatCounts.of(counted.getAvailable(), counted.getConfirmed(),
                            counted.getTotal()));
        }

        List<SeatStatusView> rows = seats.findStatusesByShow(showId);

        // Counted from the rows we are about to return, not by a second query. Two queries
        // could observe two different moments during a burst and make a correct service
        // look as though its invariant had broken.
        long available = 0;
        long confirmed = 0;
        List<SeatView> views = new ArrayList<>(rows.size());
        for (SeatStatusView row : rows) {
            if (row.getStatus() == SeatStatus.AVAILABLE) {
                available++;
            } else {
                confirmed++;
            }
            views.add(new SeatView(row.getLabel(), lowercase(row.getStatus())));
        }

        return describe(show, views, SeatCounts.of(available, confirmed, rows.size()));
    }

    /**
     * Rejects an empty or duplicated seat list, preserving the caller's ordering.
     *
     * <p>Duplicates have to be caught here. Left alone they would reach the primary key as
     * a constraint violation, which is a 500-shaped failure for what is plainly a bad
     * request.
     */
    private List<String> distinctLabelsOrReject(List<String> requested) {
        Set<String> seen = new LinkedHashSet<>(requested.size());
        List<String> duplicates = new ArrayList<>();
        for (String label : requested) {
            if (!seen.add(label)) {
                duplicates.add(label);
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiError.DUPLICATE_SEATS.asException(
                    "Seat labels must be unique. Repeated: " + duplicates.stream().distinct().toList());
        }
        return List.copyOf(seen);
    }

    private ShowResponse describe(Show show, List<SeatView> seatViews, SeatCounts counts) {
        return new ShowResponse(show.getId(), show.getName(), show.getPricePaise(),
                show.getTotalSeats(), effectivePerUserLimit(show), counts, seatViews);
    }

    /** The show's own override when set, otherwise the live global value. */
    private int effectivePerUserLimit(Show show) {
        Integer override = show.getPerUserLimit();
        return override != null ? override : config.snapshot().perUserLimit();
    }

    private static List<SeatView> seatViews(List<String> labels) {
        return labels.stream()
                .sorted()
                .map(label -> new SeatView(label, lowercase(SeatStatus.AVAILABLE)))
                .toList();
    }

    private static String lowercase(SeatStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
