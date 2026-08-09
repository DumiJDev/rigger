package io.rigger.operator.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rigger.core.domain.resource.*;
import io.rigger.store.repository.ResourceRepository;
import io.rigger.swarm.adapter.ConfigAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

/**
 * Reconciles Rigger ConfigMap resources against Docker Configs.
 * Uses docker-java ConfigAdapter — no filter encoding issues.
 */
@Component
public class ConfigMapController {

    private static final Logger log = LoggerFactory.getLogger(ConfigMapController.class);

    private final ResourceRepository store;
    private final ConfigAdapter      configAdapter;
    private final ObjectMapper       mapper = new ObjectMapper();

    public ConfigMapController(ResourceRepository store, ConfigAdapter configAdapter) {
        this.store = store; this.configAdapter = configAdapter;
    }

    public int reconcile() {
        var desired = store.findAllByKind("ConfigMap");
        var actual  = configAdapter.listManaged();
        int changes = 0;

        // Build set of existing Docker Config names
        var actualNames = actual.stream()
            .map(c -> c.getSpec() != null ? c.getSpec().getName() : null)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());

        for (var entity : desired) {
            String swarmName = "rigger-" + entity.getNamespace() + "-" + entity.getName();
            if (!actualNames.contains(swarmName)) {
                try {
                    var meta = new ObjectMeta(entity.getName(), entity.getNamespace(),
                        java.util.Map.of(), java.util.Map.of());
                    var spec = mapper.readValue(entity.getSpecJson(), ConfigMapSpec.class);
                    configAdapter.create(meta, spec);
                    changes++;
                } catch (Exception e) {
                    log.error("Failed to create ConfigMap {}/{}: {}",
                        entity.getNamespace(), entity.getName(), e.getMessage());
                }
            }
        }
        return changes;
    }
}
