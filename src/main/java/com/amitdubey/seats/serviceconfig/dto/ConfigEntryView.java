package com.amitdubey.seats.serviceconfig.dto;

import java.time.Instant;

public record ConfigEntryView(String key, String value, String description, Instant updatedAt) {
}
