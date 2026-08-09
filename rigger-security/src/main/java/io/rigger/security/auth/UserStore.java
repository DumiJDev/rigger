package io.rigger.security.auth;

import io.rigger.core.domain.security.RiggerIdentity;
import io.rigger.core.domain.security.RiggerRole;
import io.rigger.core.util.UlidGenerator;
import io.rigger.security.config.SecurityProperties;
import io.rigger.store.entity.IdentityEntity;
import io.rigger.store.repository.IdentityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * User store backed by {@link IdentityRepository} (SQLite via JPA).
 *
 * Passwords are hashed with BCrypt ({@link PasswordEncoder}) — a per-call random salt,
 * unlike the SHA-256+shared-static-salt scheme this replaced.
 *
 * This is also the single source of truth used by the auth filter —
 * replaces IdentityResolver completely.
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserStore(SecurityProperties props, IdentityRepository repository,
                      PasswordEncoder passwordEncoder, Environment env) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;

        String adminName = props.getBootstrapAdminName();
        if (!repository.existsByNameIgnoreCase(adminName)) {
            String adminPass = props.getBootstrapAdminPassword();
            boolean isProd = env.acceptsProfiles(Profiles.of("prod"));

            if (adminPass == null || adminPass.isBlank()) {
                if (isProd) {
                    throw new IllegalStateException(
                        "RIGGER_ADMIN_PASSWORD must be set when running with the 'prod' profile — " +
                        "refusing to start with no bootstrap admin password.");
                }
                adminPass = generateRandomPassword();
                log.warn("╔══════════════════════════════════════════════════════════════╗");
                log.warn("║  RIGGER_ADMIN_PASSWORD not set — generated a one-time password ║");
                log.warn("║  admin / {}", adminPass);
                log.warn("║  This is a dev/qa convenience only — set RIGGER_ADMIN_PASSWORD  ║");
                log.warn("║  explicitly for any environment that matters.                  ║");
                log.warn("╚══════════════════════════════════════════════════════════════╝");
            } else if ("admin".equals(adminPass)) {
                log.warn("Default admin password ('admin') in use! Set: RIGGER_ADMIN_PASSWORD=<your-password>");
            }

            createUser(adminName, adminPass, RiggerRole.CLUSTER_ADMIN, null);
            log.info("Bootstrap admin ready — username: '{}'", adminName);
        } else {
            log.info("Bootstrap admin already exists — username: '{}'", adminName);
        }
    }

    /** Creates a user with hashed password. Returns the created identity. */
    @Transactional
    public RiggerIdentity createUser(String username, String password,
                                     RiggerRole role, String namespace) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username must not be blank");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException("Password must not be blank");

        String hash = passwordEncoder.encode(password);
        var entity = new IdentityEntity(UlidGenerator.generate(), username, role, namespace, hash);
        repository.save(entity);
        log.info("User created: {} (role={} namespace={})", username, role, namespace);
        return toIdentity(entity);
    }

    /**
     * Authenticates username + password.
     * Returns the identity if valid, empty if not found or wrong password.
     */
    public Optional<RiggerIdentity> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        var stored = repository.findByNameIgnoreCase(username).orElse(null);
        if (stored == null) {
            log.debug("Auth failed: user not found: {}", username);
            return Optional.empty();
        }
        if (stored.getRevokedAt() != null) {
            log.debug("Auth failed: user revoked: {}", username);
            return Optional.empty();
        }
        if (!passwordEncoder.matches(password, stored.getPasswordHash())) {
            log.debug("Auth failed: wrong password for: {}", username);
            return Optional.empty();
        }
        log.debug("Auth success: {}", username);
        return Optional.of(toIdentity(stored));
    }

    /** Finds a user by username (case-insensitive). Used by the auth filter. */
    public Optional<RiggerIdentity> findByUsername(String username) {
        if (username == null) return Optional.empty();
        return repository.findByNameIgnoreCase(username).map(this::toIdentity);
    }

    public Collection<RiggerIdentity> listAll() {
        return repository.findAll().stream().map(this::toIdentity).toList();
    }

    public boolean exists(String username) {
        return username != null && repository.existsByNameIgnoreCase(username);
    }

    @Transactional
    public void revoke(String username) {
        if (username == null) return;
        repository.findByNameIgnoreCase(username).ifPresent(entity -> {
            entity.setRevokedAt(Instant.now());
            repository.save(entity);
        });
    }

    // ── private ───────────────────────────────────────────────────────────

    private RiggerIdentity toIdentity(IdentityEntity e) {
        return new RiggerIdentity(e.getId(), e.getName(), e.getRole(), e.getNamespace(),
            e.getCertSerial(), e.getCreatedAt(), e.getRevokedAt(), Map.of());
    }

    private static String generateRandomPassword() {
        var random = new SecureRandom();
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
