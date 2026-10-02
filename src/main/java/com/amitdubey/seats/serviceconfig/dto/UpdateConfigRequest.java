package com.amitdubey.seats.serviceconfig.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateConfigRequest(@NotBlank String value) {
}
