package com.amitdubey.seats.reservation;

import com.amitdubey.seats.auth.AuthenticatedUser;
import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.filter.CurrentUser;
import com.amitdubey.seats.reservation.dto.ReservationResponse;
import com.amitdubey.seats.reservation.dto.ReserveRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * Takes seats for the authenticated caller.
     *
     * <p>Returns 201 on a fresh booking and 200 when an identical earlier request is being
     * replayed, so a client can tell "I just got these seats" from "I already had them"
     * without comparing bodies.
     *
     * @param headerKey the idempotency key may arrive as a header or in the body; the header
     *                  wins, since an intermediary that retries is more likely to preserve
     *                  headers than to rewrite a body
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String headerKey,
            @Valid @RequestBody ReserveRequest request) {

        AuthenticatedUser user = CurrentUser.require();
        String idempotencyKey = resolveKey(headerKey, request.idempotencyKey());

        ReserveOutcome outcome =
                reservations.reserve(user, showId, request.seats(), idempotencyKey);

        // 201 only for a booking this request actually created. A replay is a 200: nothing
        // moved, and the caller is being handed something it already owned.
        return ResponseEntity
                .status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.response());
    }

    /** Only the owner may cancel, and cancelling twice is not an error. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId) {
        return reservations.cancel(CurrentUser.require(), reservationId);
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return reservations.find(CurrentUser.require(), reservationId);
    }

    private static String resolveKey(String headerKey, String bodyKey) {
        String key = headerKey != null && !headerKey.isBlank() ? headerKey : bodyKey;
        if (key == null || key.isBlank()) {
            throw ApiError.VALIDATION_FAILED.asException(
                    "An idempotency key is required, as the " + IDEMPOTENCY_HEADER
                            + " header or an idempotency_key field.");
        }
        if (key.length() > 200) {
            throw ApiError.VALIDATION_FAILED.asException("Idempotency key is too long.");
        }
        return key;
    }
}
