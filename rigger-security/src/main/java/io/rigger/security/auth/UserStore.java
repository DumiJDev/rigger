package io.rigger.security.auth;

import io.rigger.core.domain.security.RiggerIdentity;
import io.rigger.core.domain.security.RiggerRole;
import io.rigger.core.util.UlidGenerator;
import io.rigger.security.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory user store.
 *
 * Passwords are stored as SHA-256(salt + password) — simple and reliable
 * across all JVM implementations.
 *
 * This is also the single source of truth used by the auth filter —
 * replaces IdentityResolver completely.
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private static final String SALT = "rigger-v1-";

    private final Map<String, StoredUser> users = new ConcurrentHashMap<>();

    public UserStore(SecurityProperties props) {
        String adminName = props.getBootstrapAdminName();
        String adminPass = props.getBootstrapAdminPassword();

        if (adminPass == null || adminPass.isBlank()) adminPass = "admin";

        createUser(adminName, adminPass, RiggerRole.CLUSTER_ADMIN, null);
        log.info("Bootstrap admin ready — username: '{}'", adminName);

        if ("admin".equals(adminPass)) {
            log.warn("Default admin password in use! Set: RIGGER_ADMIN_PASSWORD=<your-password>");
        }
    }

    /** Creates a user with hashed password. Returns the created identity. */
    public RiggerIdentity createUser(String username, String password,
                                     RiggerRole role, String namespace) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username must not be blank");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException("Password must not be blank");

        String hash = hash(password);
        var identity = new RiggerIdentity(
            UlidGenerator.generate(), username, role, namespace,
            null, Instant.now(), null, Map.of()
        );
        users.put(username.toLowerCase(), new StoredUser(identity, hash));
        log.info("User created: {} (role={} namespace={})", username, role, namespace);
        return identity;
    }

    /**
     * Authenticates username + password.
     * Returns the identity if valid, empty if not found or wrong password.
     */
    public Optional<RiggerIdentity> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        var stored = users.get(username.toLowerCase());
        if (stored == null) {
            log.debug("Auth failed: user not found: {}", username);
            return Optional.empty();
        }
        if (!stored.identity().isActive()) {
            log.debug("Auth failed: user revoked: {}", username);
            return Optional.empty();
        }
        if (!hash(password).equals(stored.passwordHash())) {
            log.debug("Auth failed: wrong password for: {}", username);
            return Optional.empty();
        }
        log.debug("Auth success: {}", username);
        return Optional.of(stored.identity());
    }

    /** Finds a user by username (case-insensitive). Used by the auth filter. */
    public Optional<RiggerIdentity> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(users.get(username.toLowerCase()))
            .map(StoredUser::identity);
    }

    public Collection<RiggerIdentity> listAll() {
        return users.values().stream().map(StoredUser::identity).toList();
    }

    public boolean exists(String username) {
        return username != null && users.containsKey(username.toLowerCase());
    }

    public void revoke(String username) {
        if (username == null) return;
        users.computeIfPresent(username.toLowerCase(), (k, v) -> {
            var old = v.identity();
            var revoked = new RiggerIdentity(old.id(), old.name(), old.role(), old.namespace(),
                old.certSerial(), old.createdAt(), Instant.now(), old.metadata());
            return new StoredUser(revoked, v.passwordHash());
        });
    }

    // ── private ───────────────────────────────────────────────────────────

    private record StoredUser(RiggerIdentity identity, String passwordHash) {}

    private String hash(String password) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((SALT + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("Hash failed", e);
        }
    }
}
