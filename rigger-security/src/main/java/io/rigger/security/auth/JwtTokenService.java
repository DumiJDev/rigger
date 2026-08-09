package io.rigger.security.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import io.rigger.core.domain.security.*;
import io.rigger.security.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates JWT tokens for API/UI authentication.
 *
 * <p>The signing key is derived from rigger.security.jwtSigningKey.
 * If shorter than 32 chars, it is padded — this only happens with the
 * default dev value; production must set a proper 32+ char key.
 */
@Component
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private static final String CLAIM_ROLE      = "role";
    private static final String CLAIM_NAMESPACE = "namespace";
    private static final String CLAIM_ID        = "riggerIdentityId";

    private final SecurityProperties props;

    public JwtTokenService(SecurityProperties props) {
        this.props = props;
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
        // Ensure at least 32 bytes for HMAC-SHA256
        if (key.length() < 32) {
            key = String.format("%-32s", key).replace(' ', '0');
            log.warn("JWT signing key is too short — padding to 32 chars. Set a proper key in production.");
        }
        return Keys.hmacShaKeyFor(key.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenIdentity(String name, RiggerRole role, String namespace) {}
}
