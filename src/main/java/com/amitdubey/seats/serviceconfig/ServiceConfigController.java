package com.amitdubey.seats.serviceconfig;

import com.amitdubey.seats.filter.CurrentUser;
import com.amitdubey.seats.serviceconfig.dto.ConfigResponse;
import com.amitdubey.seats.serviceconfig.dto.UpdateConfigRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin surface for the policy settings.
 *
 * <p>The reason this exists rather than putting the values in
 * {@code application.properties}: changing a limit becomes an API call instead of a
 * redeploy. Useful operationally, and useful in a demo — "make the limit 6 right now"
 * takes one request while the service keeps serving.
 */
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class ServiceConfigController {

    private static final Logger log = LoggerFactory.getLogger(ServiceConfigController.class);

    private final ServiceConfigService config;

    @GetMapping
    public ConfigResponse read() {
        CurrentUser.requireAdmin();
        return config.describe();
    }

    @PutMapping("/{key}")
    public ConfigResponse update(@PathVariable String key,
                                 @Valid @RequestBody UpdateConfigRequest request) {
        var admin = CurrentUser.requireAdmin();
        log.warn("admin {} is setting {} to {}", admin.handle(), key, request.value());
        return config.update(key, request.value());
    }

    /** Picks up a change made directly in the database, without waiting for the schedule. */
    @PostMapping("/reload")
    public ConfigResponse reload() {
        CurrentUser.requireAdmin();
        config.refresh();
        return config.describe();
    }
}
