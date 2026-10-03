package com.amitdubey.seats.reservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code { "seats": ["A12"], "idempotency_key": "…" }}
 *
 * <p>There is deliberately no user field. A body carrying {@code user_id} is not compared
 * against the token and not rejected — it is simply never read, because unknown properties
 * are ignored. Identity comes from the signature or not at all.
 *
 * @param idempotencyKey may instead be sent as the {@code Idempotency-Key} header; the
 *                       header wins when both are present
 */
public record ReserveRequest(

        @NotEmpty
        @Size(max = 100, message = "may not request more than 100 seats in one call")
        List<
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*",
                     message = "may contain only letters, digits, dot, colon, underscore "
                             + "and hyphen")
            @Size(max = 32)
            String> seats,

        String idempotencyKey) {
}
