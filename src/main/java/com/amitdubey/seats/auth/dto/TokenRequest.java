package com.amitdubey.seats.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param handle      who to mint a token for; created on first use
 * @param adminSecret optional. When supplied and correct, the token carries the admin
 *                    role. When supplied and wrong, the request is refused rather than
 *                    quietly downgraded to a user token
 */
public record TokenRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]+",
                 message = "may contain only letters, digits, dot, underscore and hyphen")
        String handle,

        String adminSecret) {
}
