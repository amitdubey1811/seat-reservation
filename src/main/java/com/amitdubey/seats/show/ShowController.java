package com.amitdubey.seats.show;

import com.amitdubey.seats.filter.CurrentUser;
import com.amitdubey.seats.show.dto.CreateShowRequest;
import com.amitdubey.seats.show.dto.ShowResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /** Admin only: creating a show writes every seat in the hall. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@Valid @RequestBody CreateShowRequest request) {
        CurrentUser.requireAdmin();
        return showService.create(request);
    }

    /**
     * Public. Reading seat availability needs no token, and leaving it open means the
     * reconciliation invariant can be checked by anyone watching a burst.
     *
     * @param seats pass {@code false} for counts only, which avoids serialising tens of
     *              thousands of seats when all you want is the invariant
     */
    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id,
                           @RequestParam(name = "seats", defaultValue = "true") boolean seats) {
        return showService.state(id, seats);
    }
}
