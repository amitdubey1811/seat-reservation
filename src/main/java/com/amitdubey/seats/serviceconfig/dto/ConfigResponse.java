package com.amitdubey.seats.serviceconfig.dto;

import com.amitdubey.seats.serviceconfig.ConfigSnapshot;
import java.util.List;

/**
 * Both halves of the picture: what the service is actually using right now
 * ({@code effective}) and what the table says ({@code entries}).
 *
 * <p>Returning both is the point. If someone changes a row and forgets to reload, the two
 * disagree and the response shows it, instead of leaving you to guess why behaviour has
 * not changed.
 */
public record ConfigResponse(ConfigSnapshot effective, List<ConfigEntryView> entries) {
}
