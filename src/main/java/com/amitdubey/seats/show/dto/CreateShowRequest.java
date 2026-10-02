package com.amitdubey.seats.show.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code { "name": "friday-night", "seats": ["A1","A2"], "price_paise": 25000 }}
 *
 * @param name         what the show is called
 * @param seats        every seat label in the hall; must be unique, checked in the service
 * @param pricePaise   integer minor units. ₹250 is 25000. Never a float
 * @param perUserLimit optional override; null means use the global configured limit
 */
public record CreateShowRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        @NotEmpty
        @Size(max = 50_000, message = "may not contain more than 50000 seats")
        List<
            @NotBlank
            @Pattern(regexp = LABEL_PATTERN,
                     message = "may contain only letters, digits, dot, colon, underscore "
                             + "and hyphen")
            @Size(max = 32)
            String> seats,

        @Min(value = 0, message = "may not be negative")
        long pricePaise,

        @Min(value = 1, message = "must be at least 1")
        Integer perUserLimit) {

    /**
     * Deliberately excludes the comma. Seat creation passes labels to Postgres as one
     * comma-delimited string and expands them with {@code string_to_array}, so a label
     * containing a comma would split into two seats. This pattern is what makes that safe.
     */
    public static final String LABEL_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]*";
}
