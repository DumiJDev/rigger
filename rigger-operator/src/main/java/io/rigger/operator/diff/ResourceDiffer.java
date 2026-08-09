package io.rigger.operator.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Service;
import io.rigger.core.domain.resource.*;
import io.rigger.store.entity.ResourceEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Diffs desired state (ResourceEntity list from store) with actual state (docker-java Service list).
 * Matching is done by Rigger labels: rigger.io/namespace + rigger.io/name.
 */
@Component
public class ResourceDiffer {

    private static final Logger log = LoggerFactory.getLogger(ResourceDiffer.class);
    private static final String LABEL_NS        = "rigger.io/namespace";
    private static final String LABEL_NAME      = "rigger.io/name";
    private static final String LABEL_SPEC_HASH = "rigger.io/spec-hash";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Computes a ReconcilePlan using docker-java Service objects.
     */
    public <T> DockerJavaReconcilePlan diffDockerJava(
            List<ResourceEntity> desired,
            List<Service> actual,
            Class<T> specClass) {

        var actualIndex = actual.stream()
            .filter(s -> s.getSpec() != null && s.getSpec().getLabels() != null)
            .filter(s -> s.getSpec().getLabels().get(LABEL_NS) != null)
            .collect(Collectors.toMap(
                s -> s.getSpec().getLabels().get(LABEL_NS) + "/" + s.getSpec().getLabels().get(LABEL_NAME),
                s -> s,
                (a, b) -> a
            ));

        var desiredKeys = new HashSet<String>();
        var toCreate    = new ArrayList<ReconcilePlan.DesiredResource>();
        var toUpdate    = new ArrayList<DockerJavaReconcilePlan.UpdatePair>();
        int unchanged   = 0;

        for (var entity : desired) {
            String key = entity.getNamespace() + "/" + entity.getName();
            desiredKeys.add(key);

            var meta = new ObjectMeta(entity.getName(), entity.getNamespace(), Map.of(), Map.of());
            T spec;
            try {
                spec = mapper.readValue(entity.getSpecJson(), specClass);
            } catch (Exception e) {
                log.error("Failed to deserialise spec for {}: {}", key, e.getMessage());
                continue;
            }

            var existing = actualIndex.get(key);
            if (existing == null) {
                toCreate.add(new ReconcilePlan.DesiredResource(meta, spec));
            } else if (needsUpdate(entity, existing)) {
                toUpdate.add(new DockerJavaReconcilePlan.UpdatePair(existing, meta, spec));
            } else {
                unchanged++;
            }
        }

        var toDelete = actual.stream()
            .filter(s -> s.getSpec() != null && s.getSpec().getLabels() != null)
            .filter(s -> {
                String ns   = s.getSpec().getLabels().get(LABEL_NS);
                String name = s.getSpec().getLabels().get(LABEL_NAME);
                return ns != null && name != null && !desiredKeys.contains(ns + "/" + name);
            })
            .toList();

        return new DockerJavaReconcilePlan(toCreate, toUpdate, toDelete, unchanged);
    }

    /** Convenience overload — infers DeploymentSpec as specClass. */
    public DockerJavaReconcilePlan diffDockerJava(List<ResourceEntity> desired, List<Service> actual) {
        return diffDockerJava(desired, actual, io.rigger.core.domain.resource.DeploymentSpec.class);
    }

    private boolean needsUpdate(ResourceEntity entity, Service service) {
        var labels = service.getSpec() != null ? service.getSpec().getLabels() : null;
        if (labels == null) return true;
        String storedHash  = labels.get(LABEL_SPEC_HASH);
        String currentHash = Integer.toHexString(entity.getSpecJson().hashCode());
        return !currentHash.equals(storedHash);
    }
}
