package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.*;
import io.rigger.security.crypto.SecretEncryptor;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.SecretAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reconciles Rigger Secret resources against Docker Secrets.
 *
 * Values are stored encrypted (via {@link SecretEncryptor}) in Rigger's own SQLite store —
 * see {@code WorkloadController.apply()} — and decrypted here only at the point of pushing
 * the real value into Swarm, which has its own independent at-rest encryption.
 *
 * Create-only for now, matching {@link ConfigMapController}: Docker Secrets are immutable
 * once attached to a service, so updates need the same create-new-version-and-swap pattern
 * that ConfigMap reconciliation still lacks (tracked in CLAUDE.md's known gaps).
 */
@Component
public class SecretController {

    private static final Logger log = LoggerFactory.getLogger(SecretController.class);

    private final ResourceRepository store;
    private final SecretAdapter      secretAdapter;
    private final SecretEncryptor    secretEncryptor;
    private final ObjectMapper       mapper = new ObjectMapper();

    public SecretController(ResourceRepository store, SecretAdapter secretAdapter,
                             SecretEncryptor secretEncryptor) {
        this.store = store;
        this.secretAdapter = secretAdapter;
        this.secretEncryptor = secretEncryptor;
    }

    public int reconcile() {
        var desired = store.findAllByKind("Secret");
        var actual  = secretAdapter.listManaged();
        int changes = 0;

        var actualNames = actual.stream()
            .map(s -> s.getSpec() != null ? s.getSpec().getName() : null)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        for (var entity : desired) {
            String swarmName = "rigger-" + entity.getNamespace() + "-" + entity.getName();
            if (actualNames.contains(swarmName)) continue;

            try {
                var spec = mapper.readValue(entity.getSpecJson(), SecretSpec.class);
                if (spec.data() == null || spec.data().isEmpty()) {
                    // vaultRef-based secrets aren't pushed to Swarm yet — no local value to push.
                    continue;
                }
                var meta = new ObjectMeta(entity.getName(), entity.getNamespace(),
                    java.util.Map.of(), java.util.Map.of());
                var decrypted = new LinkedHashMap<String, String>();
                spec.data().forEach((key, value) -> decrypted.put(key, secretEncryptor.decrypt(value)));
                secretAdapter.create(meta, decrypted);
                changes++;
            } catch (Exception e) {
                log.error("Failed to create Secret {}/{}: {}",
                    entity.getNamespace(), entity.getName(), e.getMessage());
            }
        }
        return changes;
    }
}
