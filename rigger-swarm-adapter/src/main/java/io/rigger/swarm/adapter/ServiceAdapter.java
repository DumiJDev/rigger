package io.rigger.swarm.adapter;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.api.command.CreateServiceCmd;
import io.rigger.core.domain.resource.*;
import io.rigger.core.util.MemoryUnit;
import io.rigger.swarm.client.DockerApiException;
import io.rigger.swarm.client.DockerClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Translates Rigger Deployment resources to Docker Swarm services using docker-java.
 *
 * <p>docker-java handles all filter serialisation correctly — no manual URL encoding.
 * Filters are passed as typed objects and docker-java builds the correct JSON format
 * ({"label": ["key=value"]}) before sending to the Docker API.
 */
@Component
public class ServiceAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdapter.class);
    private static final String LABEL_NAMESPACE = "rigger.io/namespace";
    private static final String LABEL_NAME      = "rigger.io/name";
    private static final String LABEL_KIND      = "rigger.io/kind";
    private static final String LABEL_MANAGED   = "rigger.io/managed";
    private static final String LABEL_SPEC_HASH = "rigger.io/spec-hash";

    private final DockerClientFactory factory;
    private final ConfigAdapter configAdapter;

    public ServiceAdapter(DockerClientFactory factory, ConfigAdapter configAdapter) {
        this.factory = factory;
        this.configAdapter = configAdapter;
    }

    private DockerClient docker() { return factory.get(); }

    /**
     * Lists all Swarm services managed by Rigger.
     * docker-java correctly serialises the label filter as:
     *   {"label": ["rigger.io/managed=true"]}
     */
    public List<Service> listManaged() {
        try {
            return docker().listServicesCmd()
                .withLabelFilter(Map.of("rigger.io/managed", "true"))
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list managed services", e);
        }
    }

    /**
     * Finds a Rigger-managed service by namespace and name.
     */
    public Optional<Service> find(String namespace, String name) {
        try {
            return docker().listServicesCmd()
                .withLabelFilter(Map.of(
                    LABEL_NAMESPACE, namespace,
                    LABEL_NAME, name
                ))
                .exec()
                .stream()
                .findFirst();
        } catch (Exception e) {
            throw new DockerApiException("Failed to find service " + namespace + "/" + name, e);
        }
    }

    /**
     * Creates a new Swarm service from a Rigger Deployment spec.
     */
    public String create(ObjectMeta meta, DeploymentSpec spec) {
        log.info("Creating Swarm service for Deployment: {}/{}", meta.namespace(), meta.name());
        try {
            var labels = buildLabels(meta, spec);
            var serviceSpec = buildServiceSpec(meta, spec, labels);

            var response = docker().createServiceCmd(serviceSpec).exec();
            log.info("Service created: {}/{} -> swarm id={}", meta.namespace(), meta.name(), response.getId());
            return response.getId();
        } catch (Exception e) {
            throw new DockerApiException("Failed to create service " + meta.qualifiedName(), e);
        }
    }

    /**
     * Updates an existing Swarm service to match the new spec.
     * Uses the service's current version for optimistic concurrency.
     */
    public void update(Service existing, ObjectMeta meta, DeploymentSpec spec) {
        log.info("Updating Swarm service: {}/{}", meta.namespace(), meta.name());
        try {
            var labels  = buildLabels(meta, spec);
            var newSpec = buildServiceSpec(meta, spec, labels);
            long version = existing.getVersion() != null ? existing.getVersion().getIndex() : 0L;

            // buildServiceSpec never sets EndpointSpec — that's ServiceController's job
            // (publishing/updating ports for a matching Rigger Service). Without this, any
            // ordinary Deployment update (new image, more replicas, ...) would silently wipe
            // published ports until the next Service reconciliation cycle put them back.
            if (existing.getSpec() != null && existing.getSpec().getEndpointSpec() != null) {
                newSpec.withEndpointSpec(existing.getSpec().getEndpointSpec());
            }

            docker().updateServiceCmd(existing.getId(), newSpec)
                .withVersion(version)
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to update service " + meta.qualifiedName(), e);
        }
    }

    /**
     * Scales a Swarm service to the desired replica count.
     */
    public void scale(String serviceId, long currentVersion, int replicas) {
        log.info("Scaling service {} to {} replicas", serviceId, replicas);
        try {
            var services = docker().listServicesCmd()
                .withIdFilter(List.of(serviceId))
                .exec();
            if (services.isEmpty()) {
                throw new DockerApiException("Service not found: " + serviceId);
            }
            var svc  = services.get(0);
            var spec = svc.getSpec();
            if (spec == null) return;

            // Patch replica count
            var mode = spec.getMode();
            if (mode != null && mode.getReplicated() != null) {
                mode.getReplicated().withReplicas(replicas);
            }

            long version = svc.getVersion() != null ? svc.getVersion().getIndex() : currentVersion;
            docker().updateServiceCmd(serviceId, spec).withVersion(version).exec();
        } catch (DockerApiException e) {
            throw e;
        } catch (Exception e) {
            throw new DockerApiException("Failed to scale service " + serviceId, e);
        }
    }

    /**
     * Removes a Swarm service by ID.
     */
    public void delete(String serviceId) {
        log.info("Deleting Swarm service: {}", serviceId);
        try {
            docker().removeServiceCmd(serviceId).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to delete service " + serviceId, e);
        }
    }

    /**
     * Lists all running tasks for a given service.
     */
    public List<Task> listTasks(String serviceId) {
        try {
            return docker().listTasksCmd()
                .withServiceFilter(serviceId)
                .exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to list tasks for service " + serviceId, e);
        }
    }

    /**
     * Streams a container's stdout/stderr to the given output stream.
     * Blocks until the log stream completes — forever if {@code follow} is true and the
     * container keeps running, so callers should run this on a request thread they're
     * prepared to hold open (e.g. a Spring {@code StreamingResponseBody}).
     */
    public void streamLogs(String containerId, boolean follow, java.io.OutputStream out) {
        try {
            var callback = new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        out.write(frame.getPayload());
                        out.flush();
                    } catch (java.io.IOException e) {
                        onError(e);
                    }
                }
            };
            docker().logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withFollowStream(follow)
                .withTail(200)
                .exec(callback)
                .awaitCompletion();
        } catch (Exception e) {
            throw new DockerApiException("Failed to stream logs for container " + containerId, e);
        }
    }

    /**
     * Updates only the published ports (EndpointSpec) of an existing Swarm service, leaving
     * everything else (image, replicas, env, ...) untouched. Used by Service reconciliation —
     * a Rigger Service doesn't own the underlying Swarm service, it just adjusts routing.
     */
    public void updatePublishedPorts(Service existing, List<PortConfig> ports) {
        try {
            var spec = existing.getSpec();
            if (spec == null) return;
            spec.withEndpointSpec(new EndpointSpec().withPorts(ports));
            long version = existing.getVersion() != null ? existing.getVersion().getIndex() : 0L;
            docker().updateServiceCmd(existing.getId(), spec).withVersion(version).exec();
        } catch (Exception e) {
            throw new DockerApiException("Failed to update published ports for " + existing.getId(), e);
        }
    }

    // ── private builders ──────────────────────────────────────────────────

    private Map<String, String> buildLabels(ObjectMeta meta, DeploymentSpec spec) {
        var labels = new LinkedHashMap<String, String>();
        labels.put(LABEL_NAMESPACE, meta.namespace());
        labels.put(LABEL_NAME,      meta.name());
        labels.put(LABEL_KIND,      "Deployment");
        labels.put(LABEL_MANAGED,   "true");
        labels.put(LABEL_SPEC_HASH, computeSpecHash(meta, spec));
        if (meta.labels() != null) labels.putAll(meta.labels());
        return labels;
    }

    /**
     * Computes the {@code spec-hash} label value for a Deployment: the spec's own hash, folded
     * with the resolved {@code configMapRefs} Config IDs so a ConfigMap content change (which
     * doesn't touch the Deployment spec itself) still changes this value.
     *
     * <p>{@link io.rigger.operator.diff.ResourceDiffer} (in rigger-operator) must use this exact
     * same computation to decide whether a Deployment needs updating — otherwise the value it
     * compares against will never match what actually gets written here, and reconciliation
     * updates the Swarm service on every single cycle forever instead of converging.
     */
    public String computeSpecHash(ObjectMeta meta, DeploymentSpec spec) {
        String base = Integer.toHexString(spec.hashCode());
        var resolvedConfigs = resolveConfigs(meta.namespace(), spec);
        if (resolvedConfigs.isEmpty()) return base;
        String configsSignature = resolvedConfigs.stream()
            .map(ContainerSpecConfig::getConfigID)
            .sorted()
            .collect(Collectors.joining(","));
        return base + "-" + Integer.toHexString(configsSignature.hashCode());
    }

    /** Resolves configMapRefs to the currently live Docker Config for each name, skipping unresolved ones. */
    private List<ContainerSpecConfig> resolveConfigs(String namespace, DeploymentSpec spec) {
        var refs = new ArrayList<ContainerSpecConfig>();
        for (String ref : spec.configMapRefs()) {
            configAdapter.find(namespace, ref).ifPresent(cfg -> refs.add(new ContainerSpecConfig()
                .withConfigID(cfg.getId())
                .withConfigName(cfg.getSpec().getName())
                .withFile(new ContainerSpecFile().withName("/configmap/" + ref).withUid("0").withGid("0").withMode(0444L))));
        }
        return refs;
    }

    private com.github.dockerjava.api.model.ServiceSpec buildServiceSpec(ObjectMeta meta, DeploymentSpec spec, Map<String, String> labels) {
        // Container spec
        var containerSpec = new ContainerSpec()
            .withImage(spec.image());

        if (spec.env() != null) {
            var envList = spec.env().stream()
                .filter(e -> e.value() != null)
                .map(e -> e.name() + "=" + e.value())
                .collect(Collectors.toList());
            containerSpec.withEnv(envList);
        }

        var resolvedConfigs = resolveConfigs(meta.namespace(), spec);
        if (!resolvedConfigs.isEmpty()) {
            containerSpec.withConfigs(resolvedConfigs);
        }

        // Task template
        var taskTemplate = new TaskSpec()
            .withContainerSpec(containerSpec);

        // Resource limits
        if (spec.resources() != null && spec.resources().cpuLimit() != null) {
            var limits = new ResourceSpecs();
            try {
                long nanoCpu = (long)(Double.parseDouble(spec.resources().cpuLimit()) * 1_000_000_000L);
                limits.withNanoCPUs(nanoCpu);
            } catch (NumberFormatException ignored) {}
            if (spec.resources().memoryLimit() != null) {
                limits.withMemoryBytes(MemoryUnit.toBytes(spec.resources().memoryLimit()));
            }
            taskTemplate.withResources(new com.github.dockerjava.api.model.ResourceRequirements().withLimits(limits));
        }

        // Update config (rolling strategy)
        var strategy = spec.strategy() != null ? spec.strategy() : RollingUpdateStrategy.DEFAULT;
        var updateConfig = new UpdateConfig()
            .withParallelism((long) strategy.maxUnavailable())
            .withDelay(strategy.delaySeconds() * 1_000_000_000L)  // nanoseconds
            .withFailureAction(UpdateFailureAction.valueOf(
                strategy.failureAction().toUpperCase()));

        // Replicated mode
        var mode = new ServiceModeConfig()
            .withReplicated(new ServiceReplicatedModeOptions()
                .withReplicas(spec.replicas()));

        return new com.github.dockerjava.api.model.ServiceSpec()
            .withName("rigger-" + meta.namespace() + "-" + meta.name())
            .withLabels(labels)
            .withTaskTemplate(taskTemplate)
            .withMode(mode)
            .withUpdateConfig(updateConfig);
    }
}
