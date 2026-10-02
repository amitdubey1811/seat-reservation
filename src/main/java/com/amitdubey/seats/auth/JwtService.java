package com.amitdubey.seats.auth;

import com.amitdubey.seats.config.AuthProperties;
import com.amitdubey.seats.exception.ApiError;
import com.amitdubey.seats.user.AppUser;
import com.amitdubey.seats.user.UserRole;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies HS256 tokens.
 *
 * <p>The subject is the user id, so identity needs no database lookup on the hot path —
 * verifying a signature is pure computation.
 */
@Service
public class JwtService {

    static final String ISSUER = "seat-reservation";
    static final String CLAIM_HANDLE = "handle";
    static final String CLAIM_ROLE = "role";

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final Duration tokenTtl;

    public JwtService(AuthProperties properties) {
        this.algorithm = Algorithm.HMAC256(properties.getJwtSecret());
        this.verifier = JWT.require(algorithm).withIssuer(ISSUER).build();
        this.tokenTtl = properties.getTokenTtl();
    }

    public String mint(AppUser user) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(user.getId().toString())
                .withClaim(CLAIM_HANDLE, user.getHandle())
                .withClaim(CLAIM_ROLE, user.getRole().name())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(tokenTtl))
                .sign(algorithm);
    }

    public Duration tokenTtl() {
        return tokenTtl;
    }

    /**
     * Verifies a token and returns its caller.
     *
     * <p>Every failure mode — bad signature, expired, wrong issuer, unparseable subject,
     * unknown role — collapses into the same 401 with the same message. Telling a caller
     * precisely why their token was rejected is an oracle we have no reason to provide.
     *
     * @throws com.amitdubey.seats.exception.ApiException always as 401 when invalid
     */
    public AuthenticatedUser verify(String token) {
        try {
            DecodedJWT decoded = verifier.verify(token);
            return new AuthenticatedUser(
                    UUID.fromString(decoded.getSubject()),
                    decoded.getClaim(CLAIM_HANDLE).asString(),
                    UserRole.valueOf(decoded.getClaim(CLAIM_ROLE).asString()));
        } catch (JWTVerificationException | IllegalArgumentException | NullPointerException e) {
            throw ApiError.UNAUTHENTICATED.asException("Token is missing, malformed, or expired.");
        }
    }
}
