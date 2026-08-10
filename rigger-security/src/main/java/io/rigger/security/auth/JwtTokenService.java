package io.rigger.security.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.rigger.core.domain.security.*;
import io.rigger.security.config.SecurityProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates JWT tokens for API/UI authentication.
 *
 * <p>The signing key is derived from rigger.security.jwtSigningKey. With the {@code prod}
 * profile active, startup fails if the key is left at its default value or is shorter than
 * the 32 bytes HMAC-SHA256 requires. Elsewhere, a short/default key is padded with a warning —
 * a dev/qa convenience, never a substitute for setting RIGGER_JWT_KEY.
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);
    private static final String DEFAULT_KEY = "changeme-replace-in-production-min-32-chars";
    private static final int MIN_KEY_LENGTH = 32;

    private static final String CLAIM_ROLE      = "role";
    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_ID        = "riggerIdentityId";

    private final SecurityProperties props;
    private final Environment env;

    public JwtTokenService(SecurityProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @PostConstruct
    void validateSigningKey() {
        String key = props.getJwtSigningKey();
        boolean insecure = key == null || key.isBlank()
            || DEFAULT_KEY.equals(key) || key.length() < MIN_KEY_LENGTH;

        if (insecure && env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                "RIGGER_JWT_KEY must be set to a random value of at least 32 characters when " +
                "running with the 'prod' profile (e.g. `openssl rand -base64 32`) — refusing to " +
                "start with the default/short signing key.");
        }
        if (insecure) {
            log.warn("JWT signing key is default or too short — fine for dev/qa, but set " +
                "RIGGER_JWT_KEY to a random 32+ character value before this matters.");
        }
    }

    /** Issues a signed JWT for the given identity. */
    public String issue(RiggerIdentity identity) {
        var now    = Instant.now();
        var expiry = now.plusSeconds(props.getJwtExpiryMinutes() * 60L);

        return Jwts.builder()
            .subject(identity.name())
            .id(io.rigger.core.util.UlidGenerator.generate())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .issuer("rigger")
            .claim(CLAIM_ROLE,      identity.role().name())
            .claim(CLAIM_NAMESPACE, identity.namespace())
            .claim(CLAIM_ID,        identity.id())
            .signWith(signingKey())
            .compact();
    }

    /** Validates a token and returns its claims. Throws JwtException if invalid. */
    public Claims validate(String token) {
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /** Extracts identity metadata from validated claims. */
    public TokenIdentity extractIdentity(Claims claims) {
        return new TokenIdentity(
            claims.getSubject(),
            RiggerRole.valueOf(claims.get(CLAIM_ROLE, String.class)),
            claims.get(CLAIM_NAMESPACE, String.class)
        );
    }

    /** Returns the access token lifetime in seconds. */
    public int expirySeconds() {
        return props.getJwtExpiryMinutes() * 60;
    }

    // ── private ───────────────────────────────────────────────────────────

    private SecretKey signingKey() {
        String key = props.getJwtSigningKey();
        // Ensure at least 32 bytes for HMAC-SHA256. validateSigningKey() has already
        // warned (dev/qa) or failed startup (prod) if this padding is actually needed.
        if (key.length() < MIN_KEY_LENGTH) {
            key = String.format("%-32s", key).replace(' ', '0');
        }
        return Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenIdentity(String name, RiggerRole role, String namespace) {}
}
