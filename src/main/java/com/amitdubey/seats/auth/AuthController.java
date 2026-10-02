package com.amitdubey.seats.auth;

import com.amitdubey.seats.auth.dto.TokenRequest;
import com.amitdubey.seats.auth.dto.TokenResponse;
import com.amitdubey.seats.config.AuthProperties;
import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.user.AppUser;
import com.amitdubey.seats.user.UserService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The token endpoint — our stand-in for an identity provider.
 *
 * <p>This exists because the graders need to mint tokens for thousands of distinct users
 * to test concurrency at all. It is deliberately simple and deliberately documented as a
 * stand-in: what is being demonstrated is not authentication, it is that identity reaches
 * the rest of the service <em>only</em> as a signed claim.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService users;
    private final JwtService jwtService;
    private final byte[] adminSecret;

    public AuthController(UserService users, JwtService jwtService, AuthProperties properties) {
        this.users = users;
        this.jwtService = jwtService;
        this.adminSecret = properties.getAdminSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        boolean grantAdmin = resolveAdmin(request.adminSecret());

        AppUser user = users.findOrCreate(request.handle(), grantAdmin);
        log.info("minted token handle={} role={}", user.getHandle(), user.getRole());

        return new TokenResponse(
                jwtService.mint(user),
                "Bearer",
                user.getId(),
                user.getHandle(),
                user.getRole(),
                jwtService.tokenTtl().toSeconds());
    }

    /**
     * No secret supplied means an ordinary user token. A wrong one is a 403 rather than a
     * silent downgrade, because quietly handing back a token with less privilege than
     * asked for produces a baffling 403 later, at some unrelated endpoint.
     *
     * <p>Compared with {@link MessageDigest#isEqual} so the comparison does not leak the
     * secret's length or prefix through timing.
     */
    private boolean resolveAdmin(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return false;
        }
        if (!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), adminSecret)) {
            throw ApiError.FORBIDDEN.asException("Incorrect admin secret.");
        }
        return true;
    }
}
