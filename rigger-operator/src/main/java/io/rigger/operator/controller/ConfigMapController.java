package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Config;
import io.rigger.core.domain.resource.*;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ConfigAdapter;
import io.rigger.swarm.adapter.ServiceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reconciles Rigger ConfigMap resources against Docker Configs.
 *
 * <p>Docker Configs are immutable once created, so this doesn't update in place: on content
 * change it creates a new version (new name, new {@code spec-hash} label); the referencing
 * Deployment's own reconciliation (see {@link ServiceAdapter#resolveConfigs}, folded into its
 * spec-hash label) picks up the new version on its next cycle. Once a version is no longer
 * attached to any Swarm service — confirmed by scanning every managed service's
 * {@code ContainerSpec.configs} — it's safe to delete, whether superseded by a newer version or
 * because the ConfigMap itself was removed from the store.
 */
@Component
public class ConfigMapController {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapController.class);

    private final ResourceRepository store;
    private final ConfigAdapter      configAdapter;
    private final ServiceAdapter     serviceAdapter;
    private final ObjectMapper       mapper = new ObjectMapper();

    public ConfigMapController(ResourceRepository store, ConfigAdapter configAdapter, ServiceAdapter serviceAdapter) {
        this.store = store;
        this.configAdapter = configAdapter;
        this.serviceAdapter = serviceAdapter;
    }

    public int reconcile() {
        var desired = store.findAllByKind("ConfigMap");
        var actual  = configAdapter.listManaged();
        int changes = 0;

        var desiredKeys = new HashSet<String>();
        for (var entity : desired) {
            String key = entity.getNamespace() + "/" + entity.getName();
            desiredKeys.add(key);
            try {
                if (createIfMissing(entity, actual)) changes++;
            } catch (Exception e) {
                log.error("Failed to create ConfigMap {}: {}", key, e.getMessage());
            }
        }

        changes += cleanupOrphaned(actual, desiredKeys);
        return changes;
    }

    private boolean createIfMissing(io.rigger.store.entity.ResourceEntity entity, List<Config> actual) throws Exception {
        var meta = new ObjectMeta(entity.getName(), entity.getNamespace(), Map.of(), Map.of());
        var spec = mapper.readValue(entity.getSpecJson(), ConfigMapSpec.class);
        String hash = ConfigAdapter.contentHash(spec);
        String desiredName = ConfigAdapter.swarmName(meta.namespace(), meta.name(), hash);

        boolean exists = actual.stream()
            .anyMatch(c -> c.getSpec() != null && desiredName.equals(c.getSpec().getName()));
        if (exists) return false;

        configAdapter.create(meta, spec);
        return true;
    }

    /**
     * Deletes managed Config versions that are either superseded (a newer version for the same
     * ConfigMap already exists) or belong to a ConfigMap removed from the store entirely — but
     * only once no Swarm service still references them.
     */
    private int cleanupOrphaned(List<Config> actual, Set<String> desiredKeys) {
        var referencedIds = referencedConfigIds();
        int changes = 0;

        var byKey = actual.stream()
            .filter(c -> c.getSpec() != null && c.getSpec().getName() != null)
            .filter(c -> ConfigAdapter.parseFamilyKey(c.getSpec().getName()) != null)
            .collect(Collectors.groupingBy(c -> ConfigAdapter.parseFamilyKey(c.getSpec().getName())));

        for (var entry : byKey.entrySet()) {
            String key = entry.getKey();
            var versions = entry.getValue();
            boolean configMapDeleted = !desiredKeys.contains(key);

            // Keep the newest version if the ConfigMap still exists; every other version is a
            // candidate for cleanup, plus all versions if the ConfigMap itself was deleted.
            var toConsider = configMapDeleted
                ? versions
                : versions.stream()
                    .sorted(Comparator.comparing((Config c) -> c.getCreatedAt() != null ? c.getCreatedAt() : new Date(0)).reversed())
                    .skip(1)
                    .toList();

            for (var stale : toConsider) {
                if (referencedIds.contains(stale.getId())) continue; // still attached — wait for next cycle
                try {
                    configAdapter.delete(stale.getId());
                    changes++;
                } catch (Exception e) {
                    log.warn("Could not delete orphaned Config {} ({}): {}", key, stale.getId(), e.getMessage());
                }
            }
        }
        return changes;
    }

    private Set<String> referencedConfigIds() {
        var ids = new HashSet<String>();
        for (var svc : serviceAdapter.listManaged()) {
            if (svc.getSpec() == null || svc.getSpec().getTaskTemplate() == null) continue;
            var containerSpec = svc.getSpec().getTaskTemplate().getContainerSpec();
            if (containerSpec == null || containerSpec.getConfigs() == null) continue;
            containerSpec.getConfigs().forEach(c -> ids.add(c.getConfigID()));
        }
        return ids;
    }
}
