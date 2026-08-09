package io.rigger.core.domain.resource;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Spec for a Rigger Deployment resource.
 * Maps to a Docker Swarm replicated service with rolling update policy.
 *
 * @param replicas       Desired number of replicas.
 * @param selector       Label selector to match pods.
 * @param image          Container image (registry/name:tag).
 * @param env            Environment variables.
 * @param resources      CPU/memory limits.
 * @param strategy       Rolling update strategy.
 * @param hpa            Optional HPA configuration.
 * @param configMapRefs  ConfigMaps to mount as env.
 * @param secretRefs     Secrets to mount as env (values never logged).
 */
public record DeploymentSpec(
        @JsonProperty("replicas") int replicas,
        @JsonProperty("selector") Map<String, String> selector,
        @JsonProperty("image") String image,
        @JsonProperty("env") List<EnvVar> env,
        @JsonProperty("resources") ResourceRequirements resources,
        @JsonProperty("strategy") RollingUpdateStrategy strategy,
        @JsonProperty("hpa") HpaSpec hpa,
        @JsonProperty("configMapRefs") List<String> configMapRefs,
        @JsonProperty("secretRefs") List<String> secretRefs
) {
    public DeploymentSpec {
        if (replicas < 0) throw new IllegalArgumentException("replicas must be >= 0");
        if (image == null || image.isBlank()) throw new IllegalArgumentException("image must not be blank");
        if (selector == null) selector = Map.of();
        if (env == null) env = List.of();
        if (configMapRefs == null) configMapRefs = List.of();
        if (secretRefs == null) secretRefs = List.of();
        if (strategy == null) strategy = RollingUpdateStrategy.DEFAULT;
    }
}
