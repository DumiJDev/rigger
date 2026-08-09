package io.rigger.security.auth;

import io.rigger.core.domain.security.RiggerIdentity;
import org.springframework.stereotype.Component;
import java.util.Optional;

/**
 * Thin wrapper around UserStore, kept for backward compatibility.
 * All lookups delegate to UserStore.
 */
@Component
public class IdentityResolver {

    private final UserStore userStore;

    public IdentityResolver(UserStore userStore) {
        this.userStore = userStore;
    }

    public Optional<RiggerIdentity> findByName(String name) {
        return userStore.findByUsername(name);
    }
}
